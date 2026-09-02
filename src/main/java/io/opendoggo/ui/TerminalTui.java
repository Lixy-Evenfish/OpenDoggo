package io.opendoggo.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;

import io.opendoggo.permission.ApprovalPrompt;

/** Minimal full-screen terminal UI and the sole owner of terminal input. */
public final class TerminalTui implements ApprovalPrompt {

    private static final TextColor EDGE_COLOR = TextColor.ANSI.BLACK;
    private static final TextColor USER_COLOR = TextColor.ANSI.YELLOW;
    private static final TextColor TITLE_COLOR =
            new TextColor.RGB(90, 90, 90);

    /** Chat labels: purple a shade deeper than ANSI magenta. */
    private static final TextColor LABEL_COLOR =
            new TextColor.RGB(150, 40, 170);
    private static final TextColor INPUT_BACKGROUND =
            new TextColor.RGB(238, 238, 238);
    private static final TextColor INPUT_FOREGROUND =
            new TextColor.RGB(40, 40, 40);
    private static final TextColor FOOTER_COLOR =
            new TextColor.RGB(140, 140, 140);
    private static final int INPUT_HEIGHT = 3;
    private static final int FOOTER_HEIGHT = 1;
    private static final int FRAME_DELAY_MILLIS = 30;
    private static final long TIMER_FRAME_NANOS = 100_000_000L;

    /** Slash commands offered by the palette; dispatch lives in Main. */
    private static final List<Command> COMMANDS = List.of(
            new Command(
                    "/init",
                    "scan the workspace and write AGENTS.md"
            )
    );

    private final String workingDirectory;
    private final Object stateLock = new Object();
    private final List<ChatEntry> chat = new ArrayList<>();
    private final ConcurrentLinkedQueue<ChatEntry> pendingChat =
            new ConcurrentLinkedQueue<>();
    private final StringBuilder input = new StringBuilder();

    private volatile boolean running;
    private volatile boolean dirty = true;
    private volatile ApprovalRequest approval;
    private volatile TurnResult completedTurn;

    private Screen screen;
    private Thread worker;
    private boolean conversation;
    private boolean busy;
    private int cursor;
    private int scrollFromBottom;
    private volatile long turnStartedNanos;
    private volatile long segmentStartedNanos;
    private long lastFrameNanos;
    private String turnCommand;

    @FunctionalInterface
    public interface TurnHandler {
        String submit(String prompt) throws Exception;
    }

    public TerminalTui(Path workingDirectory) {
        this.workingDirectory = Objects.requireNonNull(
                workingDirectory,
                "workingDirectory cannot be null"
        ).toAbsolutePath().normalize().toString();
    }

    /** Runs until Escape, Ctrl+C, or terminal EOF. */
    public void run(TurnHandler handler) throws IOException {
        Objects.requireNonNull(handler, "handler cannot be null");

        screen = new DefaultTerminalFactory().createScreen();
        screen.startScreen();
        running = true;

        try {
            while (running) {
                if (screen.doResizeIfNecessary() != null) {
                    dirty = true;
                }

                consumePendingChat();
                consumeCompletedTurn();

                KeyStroke key = screen.pollInput();
                if (key != null) {
                    handleKey(key, handler);
                }

                if (dirty || timerFrameDue()) {
                    dirty = false;
                    render();
                    lastFrameNanos = System.nanoTime();
                }

                try {
                    Thread.sleep(FRAME_DELAY_MILLIS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        } finally {
            cancelPendingWork();
            screen.stopScreen();
            screen = null;
        }
    }

    /** Called by the agent worker; the event loop still performs the read. */
    @Override
    public boolean ask(
            String toolName,
            JsonNode toolInput,
            String reason
    ) {
        ApprovalRequest request = new ApprovalRequest(
                toolName,
                abbreviate(String.valueOf(toolInput), 300),
                reason
        );

        synchronized (stateLock) {
            if (!running) {
                return false;
            }
            approval = request;
            dirty = true;
        }

        try {
            request.answer.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }

        return request.allowed;
    }

    /**
     * Adds a completed tool call to the visible conversation.
     * The label carries the cumulative turn elapsed; the
     * input timer restarts because a new wait begins.
     */
    public void showToolResult(String toolName, String output) {
        long startedAt = turnStartedNanos;
        long now = System.nanoTime();

        String label =
                "Doggo Tool: " + sanitize(String.valueOf(toolName));
        if (startedAt != 0L) {
            label += "  " + formatElapsed(now - startedAt);
            segmentStartedNanos = now;
        }

        pendingChat.add(new ChatEntry(
                label,
                abbreviate(sanitize(String.valueOf(output)), 200)
        ));
        dirty = true;
    }

    private void handleKey(KeyStroke key, TurnHandler handler) {
        if (key.getKeyType() == KeyType.EOF
                || key.getKeyType() == KeyType.Escape
                || isCtrlC(key)) {
            running = false;
            return;
        }

        ApprovalRequest pending = approval;
        if (pending != null) {
            TerminalSize size = screen.getTerminalSize();
            if (size.getColumns() < 30 || size.getRows() < 10) {
                return;
            }
            if (isCharacter(key, 'y')) {
                answerApproval(pending, true);
            } else if (isCharacter(key, 'n')
                    || key.getKeyType() == KeyType.Enter) {
                answerApproval(pending, false);
            }
            return;
        }

        if (conversation && handleScroll(key)) {
            return;
        }

        if (busy) {
            return;
        }

        switch (key.getKeyType()) {
            case Character -> {
                input.insert(cursor, key.getCharacter());
                cursor++;
                dirty = true;
            }
            case Backspace -> {
                if (cursor > 0) {
                    int previous = previousCharacter(cursor);
                    input.delete(previous, cursor);
                    cursor = previous;
                    dirty = true;
                }
            }
            case Delete -> {
                if (cursor < input.length()) {
                    input.delete(cursor, nextCharacter(cursor));
                    dirty = true;
                }
            }
            case ArrowLeft -> {
                if (cursor > 0) {
                    cursor = previousCharacter(cursor);
                    dirty = true;
                }
            }
            case ArrowRight -> {
                if (cursor < input.length()) {
                    cursor = nextCharacter(cursor);
                    dirty = true;
                }
            }
            case Home -> {
                cursor = 0;
                dirty = true;
            }
            case End -> {
                cursor = input.length();
                dirty = true;
            }
            case Enter -> submit(handler);
            default -> {
            }
        }
    }

    private boolean handleScroll(KeyStroke key) {
        int page = Math.max(1, screen.getTerminalSize().getRows() - 7);

        switch (key.getKeyType()) {
            case PageUp -> scrollFromBottom += page;
            case PageDown -> scrollFromBottom = Math.max(
                    0,
                    scrollFromBottom - page
            );
            case ArrowUp -> scrollFromBottom++;
            case ArrowDown -> scrollFromBottom = Math.max(
                    0,
                    scrollFromBottom - 1
            );
            default -> {
                return false;
            }
        }

        dirty = true;
        return true;
    }

    private void submit(TurnHandler handler) {
        String prompt = input.toString().strip();
        if (prompt.isEmpty()) {
            return;
        }

        input.setLength(0);
        cursor = 0;
        conversation = true;
        busy = true;
        turnCommand = commandToken(prompt);
        scrollFromBottom = 0;
        chat.add(new ChatEntry("YOU", prompt));
        dirty = true;
        long startedAt = System.nanoTime();
        turnStartedNanos = startedAt;
        segmentStartedNanos = startedAt;

        worker = new Thread(() -> {
            TurnResult result;
            try {
                String response = handler.submit(prompt);
                result = new TurnResult(
                        false,
                        response == null || response.isBlank()
                                ? "(empty response)"
                                : response,
                        System.nanoTime() - startedAt
                );
            } catch (Exception exception) {
                result = new TurnResult(
                        true,
                        exception.getMessage() == null
                                ? exception.getClass().getSimpleName()
                                : exception.getMessage(),
                        System.nanoTime() - startedAt
                );
            }

            completedTurn = result;
            dirty = true;
        }, "agent-turn");
        worker.setDaemon(true);
        worker.start();
    }

    private void consumeCompletedTurn() {
        TurnResult result = completedTurn;
        if (result == null) {
            return;
        }

        completedTurn = null;
        busy = false;
        worker = null;
        turnStartedNanos = 0L;
        segmentStartedNanos = 0L;
        turnCommand = null;
        scrollFromBottom = 0;
        chat.add(new ChatEntry(
                result.error
                        ? "Error"
                        : "Doggo  " + formatElapsed(result.elapsedNanos),
                result.text
        ));
        dirty = true;
    }

    private void consumePendingChat() {
        ChatEntry entry;
        boolean added = false;
        while ((entry = pendingChat.poll()) != null) {
            chat.add(entry);
            added = true;
        }

        if (added) {
            scrollFromBottom = 0;
            dirty = true;
        }
    }

    private void answerApproval(
            ApprovalRequest request,
            boolean allowed
    ) {
        synchronized (stateLock) {
            if (approval != request) {
                return;
            }
            request.allowed = allowed;
            approval = null;
            request.answer.countDown();
            dirty = true;
        }
    }

    /** Forces periodic redraws while a turn runs so the timer advances. */
    private boolean timerFrameDue() {
        return turnStartedNanos != 0L
                && System.nanoTime() - lastFrameNanos >= TIMER_FRAME_NANOS;
    }

    private void render() throws IOException {
        TerminalSize size = screen.getTerminalSize();
        screen.clear();

        boolean tooSmall = size.getColumns() < 30
                || size.getRows() < 10;
        if (tooSmall) {
            screen.newTextGraphics().putString(0, 0, "Terminal too small");
            screen.setCursorPosition(null);
        } else if (conversation) {
            renderConversation(size);
        } else {
            renderWelcome(size);
        }

        if (!tooSmall) {
            ApprovalRequest pending = approval;
            if (pending != null) {
                renderApproval(size, pending);
                screen.setCursorPosition(null);
            }
        }

        screen.refresh();
    }

    private void renderWelcome(TerminalSize size) {
        TextGraphics graphics = screen.newTextGraphics();
        String title = "OpenDoggo";
        int titleX = Math.max(0,
                (size.getColumns() - columnWidth(title)) / 2);
        int centerY = size.getRows() / 2;

        graphics.setForegroundColor(TITLE_COLOR);
        graphics.putString(titleX, centerY - 3, title, SGR.BOLD);

        int width = Math.min(64, size.getColumns() - 4);
        int left = (size.getColumns() - width) / 2;
        renderInput(graphics, left, centerY, width);
    }

    private void renderConversation(TerminalSize size) {
        TextGraphics graphics = screen.newTextGraphics();
        List<Command> matches = matchingCommands();
        int contentHeight = size.getRows()
                - INPUT_HEIGHT
                - FOOTER_HEIGHT
                - 1
                - matches.size();
        List<DisplayLine> lines = buildDisplayLines(
                Math.max(1, size.getColumns() - 4)
        );
        int maximumScroll = Math.max(0, lines.size() - contentHeight);
        scrollFromBottom = Math.min(scrollFromBottom, maximumScroll);
        int start = Math.max(
                0,
                lines.size() - contentHeight - scrollFromBottom
        );
        int end = Math.min(lines.size(), start + contentHeight);

        int row = 1;
        for (int index = start; index < end; index++) {
            DisplayLine line = lines.get(index);
            graphics.setForegroundColor(
                    line.label && "YOU".equals(line.text)
                            ? USER_COLOR
                            : line.label
                            ? LABEL_COLOR
                            : TextColor.ANSI.DEFAULT
            );
            if (line.label) {
                graphics.putString(2, row, line.text, SGR.BOLD);
            } else {
                graphics.putString(2, row, line.text);
            }
            row++;
        }

        renderInput(
                graphics,
                1,
                size.getRows() - INPUT_HEIGHT - FOOTER_HEIGHT,
                size.getColumns() - 2
        );

        if (!matches.isEmpty()) {
            int paletteRow = size.getRows()
                    - INPUT_HEIGHT
                    - FOOTER_HEIGHT
                    - matches.size();
            for (Command command : matches) {
                graphics.setForegroundColor(LABEL_COLOR);
                graphics.putString(2, paletteRow, command.name(), SGR.BOLD);
                graphics.setForegroundColor(FOOTER_COLOR);
                graphics.putString(
                        2 + command.name().length() + 2,
                        paletteRow,
                        clip(command.description(),
                                size.getColumns() - 6)
                );
                paletteRow++;
            }
            graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        }

        graphics.setForegroundColor(FOOTER_COLOR);
        graphics.putString(
                2,
                size.getRows() - 1,
                clip(workingDirectory, size.getColumns() - 4)
        );
        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
    }

    private void renderInput(
            TextGraphics graphics,
            int left,
            int top,
            int width
    ) {
        graphics.setBackgroundColor(INPUT_BACKGROUND);
        graphics.setForegroundColor(INPUT_FOREGROUND);
        graphics.fillRectangle(
                new TerminalPosition(left, top),
                new TerminalSize(width, INPUT_HEIGHT),
                ' '
        );
        graphics.setForegroundColor(EDGE_COLOR);
        graphics.putString(left, top, "|");
        graphics.putString(left, top + 1, "|");
        graphics.putString(left, top + 2, "|");
        graphics.setForegroundColor(INPUT_FOREGROUND);

        String shown;
        int cursorColumn;
        if (busy) {
            shown = (turnCommand == null
                    ? "Thinking... "
                    : "Running " + turnCommand + "... ")
                    + formatElapsed(
                            System.nanoTime() - segmentStartedNanos
                    );
            cursorColumn = 0;
        } else {
            InputSlice slice = inputSlice(Math.max(1, width - 4));
            shown = slice.text;
            cursorColumn = slice.cursorColumn;
        }

        graphics.putString(left + 2, top + 1, shown);

        if (!busy && approval == null) {
            screen.setCursorPosition(new TerminalPosition(
                    left + 2 + cursorColumn,
                    top + 1
            ));
        } else {
            screen.setCursorPosition(null);
        }

        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
    }

    private void renderApproval(
            TerminalSize size,
            ApprovalRequest request
    ) {
        int width = Math.min(72, size.getColumns() - 4);
        int left = (size.getColumns() - width) / 2;
        List<String> detail = wrap(
                request.toolName + "(" + request.toolInput + ")",
                width - 4
        );
        int height = Math.min(
                size.getRows() - 2,
                Math.max(7, detail.size() + 6)
        );
        int top = Math.max(0, (size.getRows() - height) / 2);
        TextGraphics graphics = screen.newTextGraphics();

        graphics.fillRectangle(
                new TerminalPosition(left, top),
                new TerminalSize(width, height),
                ' '
        );
        drawBox(graphics, left, top, width, height);
        graphics.setForegroundColor(EDGE_COLOR);
        graphics.putString(
                left + 2,
                top + 1,
                clip("Permission required", width - 4),
                SGR.BOLD
        );
        graphics.setForegroundColor(TextColor.ANSI.DEFAULT);
        graphics.putString(left + 2, top + 2,
                clip(request.reason, width - 4));

        int row = top + 4;
        for (String line : detail) {
            if (row >= top + height - 2) {
                break;
            }
            graphics.putString(left + 2, row++, line);
        }

        graphics.putString(
                left + 2,
                top + height - 2,
                clip("[Y] Allow    [N/Enter] Deny", width - 4)
        );
    }

    private static void drawBox(
            TextGraphics graphics,
            int left,
            int top,
            int width,
            int height
    ) {
        graphics.setForegroundColor(EDGE_COLOR);
        graphics.putString(left, top, "+" + "-".repeat(width - 2) + "+");
        graphics.putString(
                left,
                top + height - 1,
                "+" + "-".repeat(width - 2) + "+"
        );
        for (int row = top + 1; row < top + height - 1; row++) {
            graphics.putString(left, row, "|");
            graphics.putString(left + width - 1, row, "|");
        }
    }

    /** Slash-command palette entries matching the idle input prefix. */
    private List<Command> matchingCommands() {
        if (!conversation || busy || input.length() == 0
                || input.charAt(0) != '/') {
            return List.of();
        }

        String typed = input.toString();
        List<Command> matches = new ArrayList<>();
        for (Command command : COMMANDS) {
            if (command.name().startsWith(typed)) {
                matches.add(command);
            }
        }
        return matches;
    }

    /** The COMMANDS token when this submission is a known command. */
    private static String commandToken(String prompt) {
        if (!prompt.startsWith("/")) {
            return null;
        }

        String name = prompt.split("\\s+", 2)[0];
        for (Command command : COMMANDS) {
            if (command.name().equals(name)) {
                return name;
            }
        }
        return null;
    }

    private List<DisplayLine> buildDisplayLines(int width) {
        List<DisplayLine> lines = new ArrayList<>();
        for (ChatEntry entry : chat) {
            lines.add(new DisplayLine(entry.role, true));
            for (String line : wrap(entry.text, width)) {
                lines.add(new DisplayLine(line, false));
            }
            lines.add(new DisplayLine("", false));
        }
        return lines;
    }

    private InputSlice inputSlice(int width) {
        String value = input.toString();
        int start = 0;
        while (start < cursor
                && columnWidth(value.substring(start, cursor)) >= width) {
            start += Character.charCount(value.codePointAt(start));
        }

        int end = start;
        while (end < value.length()) {
            int next = end + Character.charCount(value.codePointAt(end));
            String candidate = value.substring(start, next);
            if (columnWidth(candidate) > width) {
                break;
            }
            end = next;
        }

        return new InputSlice(
                value.substring(start, end),
                columnWidth(value.substring(start, cursor))
        );
    }

    private static List<String> wrap(String value, int width) {
        List<String> lines = new ArrayList<>();
        String[] paragraphs = sanitize(value).split("\\R", -1);

        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) {
                lines.add("");
                continue;
            }

            StringBuilder line = new StringBuilder();
            for (int index = 0; index < paragraph.length();) {
                int codePoint = paragraph.codePointAt(index);
                String character = new String(Character.toChars(codePoint));
                if (line.length() > 0
                        && columnWidth(line + character) > width) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                line.append(character);
                index += Character.charCount(codePoint);
            }
            lines.add(line.toString());
        }

        return lines;
    }

    private int previousCharacter(int index) {
        return index - Character.charCount(input.codePointBefore(index));
    }

    private int nextCharacter(int index) {
        return index + Character.charCount(input.codePointAt(index));
    }

    private static String sanitize(String value) {
        StringBuilder clean = new StringBuilder();
        String text = String.valueOf(value);

        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            if (codePoint == '\n') {
                clean.append('\n');
            } else if (codePoint == '\t') {
                clean.append("    ");
            } else if (!Character.isISOControl(codePoint)) {
                clean.appendCodePoint(codePoint);
            }
            index += Character.charCount(codePoint);
        }

        return clean.toString();
    }

    private void cancelPendingWork() {
        running = false;
        ApprovalRequest pending = approval;
        if (pending != null) {
            answerApproval(pending, false);
        }
        Thread active = worker;
        if (active != null) {
            active.interrupt();
            try {
                active.join(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean isCtrlC(KeyStroke key) {
        return key.getKeyType() == KeyType.Character
                && key.isCtrlDown()
                && key.getCharacter() != null
                && Character.toLowerCase(key.getCharacter()) == 'c';
    }

    private static boolean isCharacter(KeyStroke key, char expected) {
        return key.getKeyType() == KeyType.Character
                && key.getCharacter() != null
                && Character.toLowerCase(key.getCharacter()) == expected;
    }

    private static int columnWidth(CharSequence value) {
        return TerminalTextUtils.getColumnWidth(value.toString());
    }

    private static String clip(String value, int width) {
        List<String> lines = wrap(value, width);
        return lines.isEmpty() ? "" : lines.get(0);
    }

    private static String abbreviate(String value, int maximumLength) {
        int length = value.codePointCount(0, value.length());
        if (length <= maximumLength) {
            return value;
        }

        int retained = Math.max(0, maximumLength - 3);
        int end = value.offsetByCodePoints(0, retained);
        return value.substring(0, end) + "...";
    }

    private static String formatElapsed(long elapsedNanos) {
        return String.format(
                Locale.ROOT,
                "%.1fs",
                elapsedNanos / 1_000_000_000.0
        );
    }

    private record ChatEntry(String role, String text) {
    }

    private record Command(String name, String description) {
    }

    private record DisplayLine(String text, boolean label) {
    }

    private record InputSlice(String text, int cursorColumn) {
    }

    private record TurnResult(
            boolean error,
            String text,
            long elapsedNanos
    ) {
    }

    private static final class ApprovalRequest {
        private final String toolName;
        private final String toolInput;
        private final String reason;
        private final CountDownLatch answer = new CountDownLatch(1);
        private volatile boolean allowed;

        private ApprovalRequest(
                String toolName,
                String toolInput,
                String reason
        ) {
            this.toolName = toolName;
            this.toolInput = toolInput;
            this.reason = reason;
        }
    }
}
