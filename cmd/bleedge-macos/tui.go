//go:build darwin

package main

import (
	"context"
	"encoding/hex"
	"fmt"
	"strings"
	"unicode"

	"github.com/charmbracelet/bubbles/textinput"
	"github.com/charmbracelet/bubbles/viewport"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
	"github.com/mattn/go-runewidth"

	"github.com/bleedge/bleedge/core"
	blenode "github.com/bleedge/bleedge/macos"
)

type uiEvent struct {
	line string
	kind uiEventKind
}

type uiEventKind int

const (
	uiEventNormal uiEventKind = iota
	uiEventLog
	uiEventError
)

type nodeErrMsg struct {
	err error
}

type uiLine struct {
	text string
	kind uiEventKind
}

type tuiModel struct {
	ctx    context.Context
	cancel context.CancelFunc
	errCh  <-chan error
	events <-chan uiEvent

	node     *blenode.Node
	identity *core.Identity
	current  *blenode.Channel

	viewport viewport.Model
	input    textinput.Model
	width    int
	height   int

	lines       []uiLine
	history     []string
	histIdx     int
	chatMode    bool
	dmTarget    *core.NodeID
	dmTargetPub []byte

	completions []string
	compToken   string
	compIndex   int
}

var (
	titleStyle = lipgloss.NewStyle().Bold(true).Foreground(lipgloss.Color("39"))
	helpStyle  = lipgloss.NewStyle().Foreground(lipgloss.Color("245"))
	logStyle   = lipgloss.NewStyle().Foreground(lipgloss.Color("240"))
	errStyle   = lipgloss.NewStyle().Foreground(lipgloss.Color("203"))
)

func runTUI(ctx context.Context, cancel context.CancelFunc, errCh <-chan error, events <-chan uiEvent, node *blenode.Node, identity *core.Identity, current *blenode.Channel) error {
	ti := textinput.New()
	ti.Prompt = "> "
	ti.Placeholder = "command"
	ti.Focus()
	ti.CharLimit = 0
	ti.Width = 80

	vp := viewport.New(80, 20)
	m := tuiModel{
		ctx:      ctx,
		cancel:   cancel,
		errCh:    errCh,
		events:   events,
		node:     node,
		identity: identity,
		current:  current,
		viewport: vp,
		input:    ti,
		histIdx:  -1,
	}
	m.appendLines(true,
		fmt.Sprintf("BLEEdge macOS  node=%s  phy=1m", identity.NodeID()),
		"Advertising and scanning...",
		"Commands: trace <id> [via <hop> ...]  route <id>  topology  peers  neighbors  channels",
		"          dm <id> <text>  /join|/open <channel|nodeid|pubkey>  /close  quit",
		"Keys: Up/Down history  PageUp/PageDown scroll  Tab autocomplete",
	)

	p := tea.NewProgram(m, tea.WithAltScreen())
	return p.Start()
}

func (m tuiModel) Init() tea.Cmd {
	return tea.Batch(textinput.Blink, waitForUIEvent(m.events), waitForNodeErr(m.errCh))
}

func waitForUIEvent(events <-chan uiEvent) tea.Cmd {
	return func() tea.Msg {
		ev, ok := <-events
		if !ok {
			return uiEvent{}
		}
		return ev
	}
}

func waitForNodeErr(errCh <-chan error) tea.Cmd {
	return func() tea.Msg {
		err, ok := <-errCh
		if !ok {
			return nodeErrMsg{}
		}
		return nodeErrMsg{err: err}
	}
}

func (m tuiModel) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	var cmds []tea.Cmd
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.width = msg.Width
		m.height = msg.Height
		inputHeight := 3
		m.viewport.Width = msg.Width
		m.viewport.Height = max(1, msg.Height-inputHeight)
		m.input.Width = max(20, msg.Width-2)
		m.refreshViewport(true)
	case uiEvent:
		if msg.line != "" {
			m.appendUILines(false, uiLine{text: msg.line, kind: msg.kind})
		}
		cmds = append(cmds, waitForUIEvent(m.events))
	case nodeErrMsg:
		if msg.err != nil {
			m.appendUILines(true, uiLine{text: fmt.Sprintf("node error: %v", msg.err), kind: uiEventError})
		}
		m.cancel()
		return m, tea.Quit
	case tea.KeyMsg:
		m.resetCompletionOnEdit(msg)
		switch msg.String() {
		case "ctrl+c":
			m.cancel()
			return m, tea.Quit
		case "enter":
			line := strings.TrimSpace(m.input.Value())
			m.input.Reset()
			m.histIdx = -1
			m.clearCompletion()
			if line == "" {
				return m, nil
			}
			m.history = append(m.history, line)
			m.appendLines(true, "> "+line)
			out, quit := m.executeLine(line)
			m.appendLines(true, out...)
			if quit {
				m.cancel()
				return m, tea.Quit
			}
			return m, nil
		case "up":
			m.historyPrev()
			return m, nil
		case "down":
			m.historyNext()
			return m, nil
		case "pgup":
			m.viewport.PageUp()
			return m, nil
		case "pgdown":
			m.viewport.PageDown()
			return m, nil
		case "ctrl+up":
			m.viewport.LineUp(1)
			return m, nil
		case "ctrl+down":
			m.viewport.LineDown(1)
			return m, nil
		case "tab":
			m.complete()
			return m, nil
		}
	}

	var cmd tea.Cmd
	m.input, cmd = m.input.Update(msg)
	cmds = append(cmds, cmd)
	m.viewport, cmd = m.viewport.Update(msg)
	cmds = append(cmds, cmd)
	return m, tea.Batch(cmds...)
}

func (m tuiModel) View() string {
	status := titleStyle.Render(m.statusText())
	if m.width > 0 {
		status = lipgloss.NewStyle().Width(m.width).Render(status)
	}
	help := "tab complete • up/down history • pgup/pgdown scroll • ctrl+c quit"
	if m.chatMode {
		help = "chat mode • /close command mode • slash commands still work • " + help
	}
	return status + "\n" + m.viewport.View() + "\n" + m.input.View() + "\n" + helpStyle.Render(help)
}

func (m tuiModel) statusText() string {
	if !m.chatMode {
		return "BLEEdge command"
	}
	if m.dmTarget != nil {
		return fmt.Sprintf("BLEEdge DM %s", *m.dmTarget)
	}
	return fmt.Sprintf("BLEEdge #%s", m.current.Name)
}

func (m *tuiModel) appendLines(forceBottom bool, lines ...string) {
	if len(lines) == 0 {
		return
	}
	uiLines := make([]uiLine, 0, len(lines))
	for _, line := range lines {
		if line != "" {
			uiLines = append(uiLines, uiLine{text: line})
		}
	}
	m.appendUILines(forceBottom, uiLines...)
}

func (m *tuiModel) appendUILines(forceBottom bool, lines ...uiLine) {
	if len(lines) == 0 {
		return
	}
	wasBottom := m.viewport.AtBottom()
	for _, line := range lines {
		if line.text == "" {
			continue
		}
		m.lines = append(m.lines, line)
	}
	m.refreshViewport(forceBottom || wasBottom)
}

func (m *tuiModel) refreshViewport(bottom bool) {
	width := m.viewport.Width
	if width <= 0 {
		width = 80
	}
	rendered := make([]string, 0, len(m.lines))
	for _, line := range m.lines {
		for _, wrapped := range wrapText(line.text, width) {
			rendered = append(rendered, styleLine(wrapped, line.kind))
		}
	}
	m.viewport.SetContent(strings.Join(rendered, "\n"))
	if bottom {
		m.viewport.GotoBottom()
	}
}

func styleLine(line string, kind uiEventKind) string {
	switch kind {
	case uiEventLog:
		return logStyle.Render(line)
	case uiEventError:
		return errStyle.Render(line)
	default:
		return line
	}
}

func wrapText(s string, width int) []string {
	if width <= 1 {
		return []string{s}
	}
	var out []string
	for _, part := range strings.Split(s, "\n") {
		if part == "" {
			out = append(out, "")
			continue
		}
		for runewidth.StringWidth(part) > width {
			cut := wrapCut(part, width)
			out = append(out, strings.TrimRightFunc(part[:cut], unicode.IsSpace))
			part = strings.TrimLeftFunc(part[cut:], unicode.IsSpace)
			if part == "" {
				break
			}
		}
		if part != "" {
			out = append(out, part)
		}
	}
	return out
}

func wrapCut(s string, width int) int {
	used := 0
	lastSpace := -1
	for i, r := range s {
		if unicode.IsSpace(r) {
			lastSpace = i
		}
		next := used + runewidth.RuneWidth(r)
		if next > width {
			if lastSpace > 0 {
				return lastSpace
			}
			if i > 0 {
				return i
			}
			return len(string(r))
		}
		used = next
	}
	return len(s)
}

func (m *tuiModel) historyPrev() {
	if len(m.history) == 0 {
		return
	}
	if m.histIdx == -1 {
		m.histIdx = len(m.history) - 1
	} else if m.histIdx > 0 {
		m.histIdx--
	}
	m.input.SetValue(m.history[m.histIdx])
	m.input.CursorEnd()
}

func (m *tuiModel) historyNext() {
	if len(m.history) == 0 || m.histIdx == -1 {
		return
	}
	if m.histIdx < len(m.history)-1 {
		m.histIdx++
		m.input.SetValue(m.history[m.histIdx])
		m.input.CursorEnd()
		return
	}
	m.histIdx = -1
	m.input.Reset()
}

func (m *tuiModel) resetCompletionOnEdit(msg tea.KeyMsg) {
	switch msg.String() {
	case "tab", "shift+tab":
		return
	}
	if len(msg.Runes) > 0 || strings.Contains(msg.String(), "backspace") || strings.Contains(msg.String(), "delete") {
		m.clearCompletion()
	}
}

func (m *tuiModel) clearCompletion() {
	m.completions = nil
	m.compToken = ""
	m.compIndex = 0
}

func (m *tuiModel) complete() {
	value := m.input.Value()
	pos := m.input.Position()
	token, start, end := completionToken(value, pos)
	if token == "" && m.chatMode && !strings.HasPrefix(value, "/") {
		return
	}
	if token != m.compToken || len(m.completions) == 0 {
		m.compToken = token
		m.completions = m.completionCandidates(value, token)
		m.compIndex = 0
	} else {
		m.compIndex = (m.compIndex + 1) % len(m.completions)
	}
	if len(m.completions) == 0 {
		return
	}
	repl := m.completions[m.compIndex]
	next := value[:start] + repl + value[end:]
	m.input.SetValue(next)
	m.input.SetCursor(start + len(repl))
}

func completionToken(s string, pos int) (token string, start int, end int) {
	if pos > len(s) {
		pos = len(s)
	}
	start = pos
	for start > 0 && s[start-1] != ' ' {
		start--
	}
	end = pos
	for end < len(s) && s[end] != ' ' {
		end++
	}
	return s[start:pos], start, end
}

func (m tuiModel) completionCandidates(line, token string) []string {
	var base []string
	fields := strings.Fields(line)
	cmds := []string{"channels", "close", "dm", "help", "neighbors", "peers", "quit", "route", "topology", "trace"}
	slashCmds := []string{"/channels", "/close", "/dm", "/help", "/join", "/neighbors", "/open", "/peers", "/quit", "/route", "/topology", "/trace"}
	cmd := ""
	if len(fields) > 0 {
		cmd = strings.TrimPrefix(fields[0], "/")
	}
	if strings.HasPrefix(token, "/") || (len(fields) == 0 && strings.HasPrefix(line, "/")) {
		base = slashCmds
	} else if len(fields) <= 1 {
		if !m.chatMode {
			base = cmds
		}
	} else if cmd == "join" || cmd == "open" {
		base = []string{"public"}
		for _, ch := range m.node.Channels() {
			base = append(base, ch.Name)
		}
		base = append(base, m.nodeIDCandidates()...)
	} else if cmd == "trace" && strings.HasPrefix("via", token) {
		base = []string{"via"}
	} else {
		base = m.nodeIDCandidates()
	}
	seen := map[string]bool{}
	out := make([]string, 0, len(base))
	for _, c := range base {
		if seen[c] || !strings.HasPrefix(strings.ToLower(c), strings.ToLower(token)) {
			continue
		}
		seen[c] = true
		out = append(out, c)
	}
	return out
}

func (m tuiModel) nodeIDCandidates() []string {
	var out []string
	for _, id := range m.node.ConnectedPeers() {
		out = append(out, id.String())
	}
	for _, nb := range m.node.Neighbors() {
		out = append(out, nb.ID.String())
	}
	for _, tn := range m.node.Topology() {
		out = append(out, tn.ID.String())
		for _, nb := range tn.Neighbors {
			out = append(out, nb.String())
		}
	}
	return out
}

func (m *tuiModel) executeLine(line string) ([]string, bool) {
	if m.chatMode && !strings.HasPrefix(line, "/") {
		return m.sendChatLine(line), false
	}

	cmdLine := strings.TrimSpace(line)
	if strings.HasPrefix(cmdLine, "/") {
		cmdLine = strings.TrimPrefix(cmdLine, "/")
	}
	fields := strings.Fields(cmdLine)
	if len(fields) == 0 {
		return nil, false
	}
	cmd := fields[0]
	args := strings.TrimSpace(strings.TrimPrefix(cmdLine, cmd))

	switch cmd {
	case "quit", "q", "exit":
		return []string{"exiting"}, true
	case "help", "h":
		return []string{
			"commands: trace, route, topology, peers, neighbors, channels, dm, quit",
			"chat: /join|/open <public|channel-name|16-byte-channel-secret|nodeid|pubkey>, /close",
		}, false
	case "peers", "p":
		return m.renderPeers(), false
	case "neighbors", "n":
		return m.renderNeighbors(), false
	case "topology", "t":
		return m.renderTopology(), false
	case "channels", "c":
		return m.renderChannels(), false
	case "close":
		m.chatMode = false
		m.dmTarget = nil
		m.dmTargetPub = nil
		m.updateInputPlaceholder()
		return []string{"command mode"}, false
	}

	if cmd == "join" || cmd == "open" {
		return m.openChat(args), false
	}
	if cmd == "dm" {
		parts := strings.SplitN(args, " ", 2)
		if len(parts) < 2 {
			return []string{"usage: /dm <nodeid> <message>"}, false
		}
		id, err := core.ParseNodeID(parts[0])
		if err != nil {
			return []string{fmt.Sprintf("bad node id: %v", err)}, false
		}
		if err := m.node.SendChat(id, parts[1]); err != nil {
			return []string{fmt.Sprintf("dm error: %v", err)}, false
		}
		return []string{fmt.Sprintf("dm sent to %s", id)}, false
	}
	if cmd == "route" {
		id, err := core.ParseNodeID(args)
		if err != nil {
			return []string{fmt.Sprintf("bad node id: %v", err)}, false
		}
		route, ok := m.node.RouteTo(id)
		if !ok {
			return []string{fmt.Sprintf("no route known to %s", id)}, false
		}
		return []string{fmt.Sprintf("route to %s: %s", id, formatRoute(route))}, false
	}
	if cmd == "trace" {
		dst, route, err := parseTraceCommand(args)
		if err != nil {
			return []string{fmt.Sprintf("usage: /trace <nodeid> [via <hop1> <hop2> ...]  (%v)", err)}, false
		}
		tag, err := m.node.SendTrace(dst, route)
		if err != nil {
			return []string{fmt.Sprintf("trace error: %v", err)}, false
		}
		if len(route) == 0 {
			return []string{fmt.Sprintf("trace sent to %s tag=%08x (auto route)", dst, tag)}, false
		}
		return []string{fmt.Sprintf("trace sent to %s tag=%08x route=%s", dst, tag, formatRoute(route))}, false
	}

	return []string{"unknown command. try help, trace, route, topology, peers, neighbors, channels, /join, /open"}, false
}

func (m *tuiModel) sendChatLine(line string) []string {
	if m.dmTarget != nil {
		var err error
		if len(m.dmTargetPub) == 32 {
			err = m.node.SendChatTo(*m.dmTarget, m.dmTargetPub, line)
		} else {
			err = m.node.SendChat(*m.dmTarget, line)
		}
		if err != nil {
			return []string{fmt.Sprintf("dm error: %v", err)}
		}
		return []string{fmt.Sprintf("dm sent to %s", *m.dmTarget)}
	}
	if err := m.node.SendToChannel(m.current.Secret, line); err != nil {
		return []string{fmt.Sprintf("send error: %v", err)}
	}
	return []string{fmt.Sprintf("sent to #%s: %s", m.current.Name, line)}
}

func (m *tuiModel) openChat(target string) []string {
	target = strings.TrimSpace(target)
	if target == "" {
		return []string{"usage: /join <public|channel-name|16-byte-channel-secret|nodeid|pubkey>"}
	}
	m.dmTarget = nil
	m.dmTargetPub = nil
	switch {
	case strings.EqualFold(target, "public"):
		m.current = m.node.JoinPublicChannel()
	case isHexLen(target, core.ChannelSecretLen*2):
		secret, err := hex.DecodeString(target)
		if err != nil {
			return []string{fmt.Sprintf("bad channel secret: %v", err)}
		}
		m.current = m.node.JoinChannel(secret, target[:8])
	case isHexLen(target, 16):
		id, err := core.ParseNodeID(target)
		if err != nil {
			return []string{fmt.Sprintf("bad node id: %v", err)}
		}
		m.dmTarget = &id
	case isHexLen(target, 64):
		pub, err := hex.DecodeString(target)
		if err != nil {
			return []string{fmt.Sprintf("bad pubkey: %v", err)}
		}
		id := core.NodeIDFromPubKey(pub)
		m.dmTarget = &id
		m.dmTargetPub = pub
	default:
		m.current = m.node.JoinNamedChannel(target)
	}
	m.chatMode = true
	m.updateInputPlaceholder()
	if m.dmTarget != nil {
		return []string{fmt.Sprintf("opened DM with %s", *m.dmTarget)}
	}
	return []string{fmt.Sprintf("opened #%s (hash=0x%02x)", m.current.Name, m.current.Hash)}
}

func (m *tuiModel) updateInputPlaceholder() {
	if m.chatMode {
		m.input.Placeholder = "message"
		return
	}
	m.input.Placeholder = "command"
}

func isHexLen(s string, n int) bool {
	if len(s) != n {
		return false
	}
	_, err := hex.DecodeString(s)
	return err == nil
}

func (m tuiModel) renderPeers() []string {
	peers := m.node.ConnectedPeers()
	if len(peers) == 0 {
		return []string{"(no peers connected)"}
	}
	out := make([]string, 0, len(peers))
	for _, p := range peers {
		out = append(out, fmt.Sprintf("peer: %s  %s%s  %s",
			p, nameLabel(m.node.NameFor(p)), platLabel(m.node.PlatformFor(p)), descLabel(m.node.DescriptionFor(p))))
	}
	return out
}

func (m tuiModel) renderNeighbors() []string {
	nbs := m.node.Neighbors()
	if len(nbs) == 0 {
		return []string{"(no neighbors)"}
	}
	out := make([]string, 0, len(nbs))
	for _, nb := range nbs {
		out = append(out, fmt.Sprintf("neighbor: %s  %s%s  rssi=%d  tx=%s  rx=%s",
			nb.ID, nameLabel(m.node.NameFor(nb.ID)), platLabel(m.node.PlatformFor(nb.ID)), nb.RSSI, nb.TxPHY, nb.RxPHY))
	}
	return out
}

func (m tuiModel) renderTopology() []string {
	nodes := m.node.Topology()
	if len(nodes) == 0 {
		return []string{"(no topology data)"}
	}
	out := make([]string, 0, len(nodes))
	for _, tn := range nodes {
		nbs := make([]string, len(tn.Neighbors))
		for i, id := range tn.Neighbors {
			s := id.String()
			if len(s) > 8 {
				s = s[:8] + "..."
			}
			nbs[i] = s
		}
		out = append(out, fmt.Sprintf("node: %s  %s%s  caps=%s  last-announce=%s  neighbors=[%s]",
			tn.ID, nameLabel(m.node.NameFor(tn.ID)), platLabel(tn.Platform), tn.Caps, relativeTime(tn.LastSeen), strings.Join(nbs, " ")))
	}
	return out
}

func (m tuiModel) renderChannels() []string {
	var out []string
	for _, ch := range m.node.Channels() {
		marker := " "
		if string(ch.Secret) == string(m.current.Secret) {
			marker = "*"
		}
		out = append(out, fmt.Sprintf("%s #%s  hash=0x%02x", marker, ch.Name, ch.Hash))
	}
	return out
}
