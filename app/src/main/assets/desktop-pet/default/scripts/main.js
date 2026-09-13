let root;
const icons = {
  idle: '<path d="M5 5h14v11H9l-4 3V5Z"/><path d="M8 9h8M8 12h5"/>',
  thinking: '<circle cx="12" cy="12" r="8"/><path d="M12 7v5l3 2"/>',
  executing: '<path d="m14 3-8 10h6l-2 8 8-11h-6l2-7Z"/>',
  approval: '<path d="M12 3 2 21h20L12 3Z"/><path d="M12 9v5M12 17v.1"/>',
  completed: '<path d="m5 12 4 4L19 6"/>',
  paused: '<path d="M8 5v14M16 5v14"/>',
  error: '<circle cx="12" cy="12" r="9"/><path d="M12 7v6M12 17v.1"/>'
};
function render(state) {
  root.dataset.state = icons[state] ? state : 'idle';
  root.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${icons[root.dataset.state]}</svg>`;
}
export default {
  mount(api) {
    root = api.root;
    const style = document.createElement('style');
    style.textContent = '#pet-root{width:92%;height:92%;margin:4%;border-radius:50%;background:#5376B9;display:grid;place-items:center}#pet-root svg{width:52%;height:52%}';
    root.append(style); document.head.append(style);
    render('idle');
  },
  onEvent(type, data) { if (type === 'state') render(data.state); },
  unmount() { root?.replaceChildren(); }
};
