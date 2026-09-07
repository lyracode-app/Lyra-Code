import {feedback} from './behaviors/feedback.js';
let sprite, animations, current = '';
async function play(name, fps) {
  const key = name + ':' + fps;
  if (key === current) return;
  current = key;
  await sprite.play({...animations[name], fps});
}
export default {
  async mount(api) {
    animations = await api.json('data/animations.json');
    sprite = api.createSprite();
    await play('PNG', 4);
    window.petReady = true;
  },
  onEvent(type, data, api) {
    if (type === 'config') play(data.animation || 'PNG', data.fps || 4).catch(console.error);
    if (type === 'state') sprite.element.style.filter = data.state === 'thinking' ? 'hue-rotate(60deg)' : '';
    if (type === 'test-animation') play(data.name, 4).catch(console.error);
    feedback(sprite.element, type);
  },
  unmount() { sprite?.destroy(); }
};
