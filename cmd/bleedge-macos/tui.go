//go:build darwin

package main

import (
	"context"
	"fmt"
	"strings"

	"github.com/charmbracelet/bubbles/textinput"
	"github.com/charmbracelet/bubbles/viewport"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"

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

	lines   []string
	history []string
	histIdx int

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
	ti.Placeholder = "message or /command"
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
		"Commands: /dm <id> <text>  /trace <id> [via <hop> ...]  /route <id>  /join <name>|public",
		"          /channels  /peers  /neighbors  /topology  /quit",
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
			m.appendLines(false, styleUIEvent(msg))
		}
		cmds = append(cmds, waitForUIEvent(m.events))
	case nodeErrMsg:
		if msg.err != nil {
			m.appendLines(true, errStyle.Render(fmt.Sprintf("node error: %v", msg.err)))
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

func styleUIEvent(ev uiEvent) string {
	switch ev.kind {
	case uiEventLog:
		return logStyle.Render(ev.line)
	case uiEventError:
		return errStyle.Render(ev.line)
	default:
		return ev.line
	}
}

func (m tuiModel) View() string {
	status := titleStyle.Render(fmt.Sprintf("BLEEdge #%s", m.current.Name))
	if m.width > 0 {
		status = lipgloss.NewStyle().Width(m.width).Render(status)
	}
	return status + "\n" + m.viewport.View() + "\n" + m.input.View() + "\n" + helpStyle.Render("tab complete • up/down history • pgup/pgdown scroll • ctrl+c quit")
}

func (m *tuiModel) appendLines(forceBottom bool, lines ...string) {
	if len(lines) == 0 {
		return
	}
	wasBottom := m.viewport.AtBottom()
	for _, line := range lines {
		if line == "" {
			continue
		}
		m.lines = append(m.lines, line)
	}
	m.refreshViewport(forceBottom || wasBottom)
}

func (m *tuiModel) refreshViewport(bottom bool) {
	m.viewport.SetContent(strings.Join(m.lines, "\n"))
	if bottom {
		m.viewport.GotoBottom()
	}
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
	if token == "" && !strings.HasPrefix(value, "/") {
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
	if strings.HasPrefix(token, "/") || (len(fields) == 0 && strings.HasPrefix(line, "/")) {
		base = []string{"/channels", "/dm", "/join", "/neighbors", "/peers", "/quit", "/route", "/topology", "/trace"}
	} else if len(fields) > 0 && fields[0] == "/join" {
		base = []string{"public"}
		for _, ch := range m.node.Channels() {
			base = append(base, ch.Name)
		}
	} else if len(fields) > 0 && fields[0] == "/trace" && strings.HasPrefix("via", token) {
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
	switch line {
	case "/quit", "/q", "/exit":
		return []string{"exiting"}, true
	case "/peers", "/p":
		return m.renderPeers(), false
	case "/neighbors", "/n":
		return m.renderNeighbors(), false
	case "/topology", "/t":
		return m.renderTopology(), false
	case "/channels", "/c":
		return m.renderChannels(), false
	}

	if strings.HasPrefix(line, "/join ") {
		name := strings.TrimSpace(strings.TrimPrefix(line, "/join "))
		if name == "" {
			return []string{"usage: /join <name>  (use 'public' for the Public channel)"}, false
		}
		if strings.EqualFold(name, "public") {
			m.current = m.node.JoinPublicChannel()
		} else {
			m.current = m.node.JoinNamedChannel(name)
		}
		return []string{fmt.Sprintf("joined #%s (hash=0x%02x)", m.current.Name, m.current.Hash)}, false
	}
	if strings.HasPrefix(line, "/dm ") {
		rest := strings.TrimSpace(strings.TrimPrefix(line, "/dm "))
		parts := strings.SplitN(rest, " ", 2)
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
	if strings.HasPrefix(line, "/route ") {
		id, err := core.ParseNodeID(strings.TrimSpace(strings.TrimPrefix(line, "/route ")))
		if err != nil {
			return []string{fmt.Sprintf("bad node id: %v", err)}, false
		}
		route, ok := m.node.RouteTo(id)
		if !ok {
			return []string{fmt.Sprintf("no route known to %s", id)}, false
		}
		return []string{fmt.Sprintf("route to %s: %s", id, formatRoute(route))}, false
	}
	if strings.HasPrefix(line, "/trace ") {
		dst, route, err := parseTraceCommand(strings.TrimSpace(strings.TrimPrefix(line, "/trace ")))
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
	if strings.HasPrefix(line, "/") {
		return []string{"unknown command. try /dm /trace /route /join /channels /peers /neighbors /topology /quit"}, false
	}
	if err := m.node.SendToChannel(m.current.Secret, line); err != nil {
		return []string{fmt.Sprintf("send error: %v", err)}, false
	}
	return []string{fmt.Sprintf("sent to #%s: %s", m.current.Name, line)}, false
}

func (m tuiModel) renderPeers() []string {
	peers := m.node.ConnectedPeers()
	if len(peers) == 0 {
		return []string{"(no peers connected)"}
	}
	out := make([]string, 0, len(peers))
	for _, p := range peers {
		out = append(out, fmt.Sprintf("peer: %s  %s", p, descLabel(m.node.DescriptionFor(p))))
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
		out = append(out, fmt.Sprintf("neighbor: %s  %s  rssi=%d  tx=%s  rx=%s",
			nb.ID, descLabel(m.node.DescriptionFor(nb.ID)), nb.RSSI, nb.TxPHY, nb.RxPHY))
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
		out = append(out, fmt.Sprintf("node: %s  %s  caps=%s  last-announce=%s  neighbors=[%s]",
			tn.ID, descLabel(tn.Description), tn.Caps, relativeTime(tn.LastSeen), strings.Join(nbs, " ")))
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
