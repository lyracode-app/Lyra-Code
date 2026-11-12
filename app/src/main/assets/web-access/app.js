'use strict';
const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let token = sessionStorage.getItem('lyra-web-token') || '', state = null, tab = 'prompts', historyTab = 'project', polling = false, busy = false, uploading = false, connected = false;
let lastMessages = '', lastHistory = '', lastInteraction = '', settingsFingerprint = '', toastTimer, workspaces = [], workspaceStack = [], needsStoragePermission = false;
let lastStateRequest = 0, stateRenderFrame = 0;
const drafts = new Map();
const expandedProjects = new Map();
const icon = name => `<svg class="icon" aria-hidden="true"><use href="#i-${name}"/></svg>`;
const settingPages = {
  prompts:["提示词","选择主 Agent 的提示词，或维护自己的工作方式。"],
  agents:["子代理","为主 Agent 配置可调用的模型与职责。"],
  compression:["上下文 / 压缩","查看上下文用量，调整自动压缩与摘要模型。"],
  tools:['Agent Tools',"管理 Agent 可用工具，展开卡片查看工具说明。"],
  mcp:["MCP 服务器","管理连接配置，并同步远程工具列表。"]
};
const themeRoles = ['background','on-background','surface','surface-low','surface-high','surface-highest','on-surface','on-surface-variant','outline','outline-variant','primary','on-primary','secondary','error','error-container','on-error-container','inverse-surface','inverse-on-surface'];
function applyTheme() {
  const theme = state.theme; if (!theme?.colors) return;
  document.documentElement.dataset.scheme = theme.dark ? 'dark' : 'light';
  document.documentElement.style.colorScheme = theme.dark ? 'dark' : 'light';
  for (const role of themeRoles) if (/^#[\da-f]{6}$/i.test(theme.colors[role] || '')) document.documentElement.style.setProperty(`--${role}`,theme.colors[role]);
}
const choices = (items, value) => items.map(([id, name]) => `<option value="${esc(id)}" ${id === value ? 'selected' : ''}>${esc(name)}</option>`).join('');
const profileOptions = value => choices(state.profiles.map(p => [p.id, p.name]), value);
const session = () => ({instanceId:state.instanceId,conversationId:state.conversationId, projectId:state.projectId, sessionRevision:state.sessionRevision});
const message = text => { $('toast').textContent = text; $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').hidden = true, 4500); };
function showLogin(error = '') { connected = false; $('settings').close(); $('dialog').close(); $('reasoning-dialog').close(); $('visual-dialog').close(); stateStream.stop(); $('app').hidden = true; $('login').hidden = false; $('login-error').textContent = error; }
async function api(path, options = {}) {
  const response = await fetch(path, {...options, headers:{...options.headers, Authorization:`Bearer ${token}`}, cache:'no-store'});
  const data = await response.json();
  if (!response.ok) { if (response.status === 401) { sessionStorage.removeItem('lyra-web-token'); showLogin(t(data.error)); } throw new Error(t(data.error) || `HTTP ${response.status}`); }
  return data;
}
async function action(actionName, args = {}, refresh = true) {
  const data = await api('/api/action', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({action:actionName, ...session(), ...args})});
  if (refresh) await sync();
  return data;
}
async function safe(work) { try { return await work(); } catch (e) { message(e.message); } }
function preserveFields(root, work) {
  const fields = [...root.querySelectorAll('input,textarea,select')].map(el => ({id:el.id,name:el.name,value:el.value,checked:el.checked,
    focused:el===document.activeElement,start:el.selectionStart,end:el.selectionEnd}));
  work();
  const nodes=[...root.querySelectorAll('input,textarea,select')];
  fields.forEach((field,i) => {
    const el=nodes[i]; if (!el || el.id!==field.id || el.name!==field.name) return;
    el.value=field.value; if ('checked' in el) el.checked=field.checked;
    if (field.focused) { el.focus({preventScroll:true}); if (typeof field.start==='number') el.setSelectionRange(field.start,field.end); }
  });
}
function applyLocale(locale) {
  if (!setInterfaceLocale(locale)) return;
  lastMessages=lastHistory=lastInteraction=settingsFingerprint='';
  if (!state) return;
  preserveFields($('interaction'),renderInteraction);
  if ($('settings').open) {
    preserveFields($('settings-content'),renderSettings);
    settingsFingerprint = JSON.stringify([tab,state.prompts,state.promptId,state.purePrompt,state.subAgents,state.subAgentsEnabled,state.tools,state.mcpServers,state.compression]);
  }
  if ($('dialog').open && dialogRefresh) preserveFields($('dialog-content'),dialogRefresh);
}
async function syncLoginLocale() {
  const response=await fetch('/api/locale',{cache:'no-store'});
  if (response.ok) applyLocale(await response.json());
}
async function sync() {
  if (polling) return;
  polling = true;
  try {
    const requestToken = token;
    const next = await api('/api/state');
    if (!token || token !== requestToken) return;
    lastStateRequest=Date.now();acceptState(next); stateStream.start();
  } catch (e) {
    connected = false;
    $('connection').classList.add('offline'); $('connection').innerHTML = `<i></i> ${esc(t("连接中断 · 自动重试"))}`;
    if (!state) throw e;
  } finally { polling = false; }
}
async function poll() { if (token && !$('app').hidden) { if (!stateStream.connected || Date.now()-lastStateRequest>3000) await safe(sync); } else await safe(syncLoginLocale); setTimeout(poll, state?.running ? 250 : 1000); }
function setSelect(id, html, value) { const el = $(id); if (document.activeElement !== el && el.innerHTML !== html) el.innerHTML = html; if (document.activeElement !== el) el.value = value; }
function render() {
  cancelAnimationFrame(stateRenderFrame);stateRenderFrame=0;
  applyTheme();
  $('main').classList.toggle('empty',!state.messages.length && !state.approval && !state.question);
  $('main').classList.toggle('loading',state.loading);
  $('chat-title').textContent = state.title;
  $('workspace-label').textContent = state.workspace.name || state.workspace.path?.split('/').filter(Boolean).at(-1) || t("选择工作目录");
  $('workspace').title = state.workspace.path || state.workspace.uri;
  $('prompt-label').textContent = state.prompts.find(p => p.id === state.promptId)?.name || t("原生提示词");
  const modelValue = `${state.profileId}\n${state.model}`;
  const models = state.profiles.map(p => `<optgroup label="${esc(p.name)}">${choices([...new Set([...p.models, ...(p.id === state.profileId ? [state.model] : [])])].map(m => [`${p.id}\n${m}`,m]),modelValue)}</optgroup>`).join('');
  setSelect('model-select', models,modelValue);
  reasoningControl.sync(state);
  $('auto-approval').checked = state.autoApproval; $('approval-label').textContent = state.autoApproval ? t("无审批模式") : t("逐次审批");
  const deliveryEnabled = !!state.composerDelivery?.enabled;
  $('run-state').textContent = state.compressing ? t("正在压缩…") : state.running ? t("正在运行…") : '';
  if (deliveryEnabled && state.composerDelivery?.queued > 0) $('run-state').textContent += t(" · {count} 条排队", {count: state.composerDelivery.queued});
  $('context-label').textContent = `${t("上下文 ·")} ${state.context.tokens.toLocaleString()}`;
  $('status').textContent = state.status; $('stop').hidden = !state.running; $('send').hidden = state.running && !deliveryEnabled; $('continue').hidden = !state.canContinue;
  renderPerformance();
  $('send').disabled = busy || uploading || (!state.uploads.length && !$('input').value.trim()) || !connected;
  $('attach').disabled = uploading || (state.running && !deliveryEnabled); $('input').disabled = busy;
  $('upload-list').innerHTML = state.uploads.map(f => `<span class="file-chip">▧ ${esc(f.name)} <small>${(f.size/1024).toFixed(1)} KB</small><button data-remove-upload="${esc(f.uri)}" aria-label="${esc(t("移除附件"))}">×</button></span>`).join('');
  renderHistory(); renderMessages(); renderInteraction();
  if (!$('settings').hidden) {
    const fingerprint = JSON.stringify([tab,state.prompts,state.promptId,state.purePrompt,state.subAgents,state.subAgentsEnabled,state.tools,state.mcpServers,state.compression]);
    if (fingerprint !== settingsFingerprint && !$('settings-content').contains(document.activeElement)) { settingsFingerprint = fingerprint; renderSettings(); }
    if (tab === 'tools') updateToolStates();
    updateMetrics();
  }
}
function renderPerformance() {
  let latest;
  for(let index=state.messages.length-1;index>=0;index--) {
    const record=state.messages[index];
    if(record.role==='user')break;
    if(record.role==='assistant'){latest=record;break;}
  }
  const speed=latest?.tokensPerSecond,rate=latest?.deepSeekCacheHitRate;
  const speedNode=$('tokens-per-second'),cacheNode=$('cache-hit-rate');
  speedNode.hidden=!(Number.isFinite(speed) && speed>0);
  speedNode.textContent=speedNode.hidden?'':`${speed.toFixed(speed>=10?0:1)} tok/s`;
  speedNode.title=t('输出速度');
  cacheNode.hidden=!Number.isFinite(rate);
  cacheNode.textContent=cacheNode.hidden?'':t('缓存命中率 {rate}%',{rate:Math.max(0,Math.min(100,rate)).toFixed(1)});
}
// Merge every delta immediately, but update the DOM at most once per browser frame.
function scheduleStateRender() {
  if(document.hidden || stateRenderFrame)return;
  stateRenderFrame=requestAnimationFrame(()=>{stateRenderFrame=0;if(state && !$('app').hidden)render();});
}
document.addEventListener('visibilitychange',()=>{if(!document.hidden)scheduleStateRender();});
function renderHistory() {
  const query = $('history-search').value.trim().toLowerCase(), fingerprint = JSON.stringify([historyTab,query,state.conversations,state.projects,state.conversationId,state.projectId,state.workspace.uri]);
  if (fingerprint === lastHistory) return; lastHistory = fingerprint;
  const row = c => `<div class="history-row ${c.id === state.conversationId ? 'selected' : ''}"><button class="history-item" data-select="${c.id}" title="${esc(c.title)}">${c.pinned ? '⌑ ' : ''}${esc(c.title)}</button><button class="history-menu" data-chat-menu="${c.id}" aria-label="${esc(t("会话操作："))}${esc(c.title)}">⋯</button></div>`;
  const matches = c => c.title.toLowerCase().includes(query);
  if (historyTab === 'normal') {
    const chats = state.conversations.filter(c => !c.projectId && matches(c));
    $('history').innerHTML = `<div class="history-section"><div class="section-title">${esc(t("最近会话"))}</div>${chats.filter(c => !c.archived).map(row).join('') || `<p class="history-empty">${esc(t("暂无会话"))}</p>`}</div><details class="archive-list"><summary class="section-title">${esc(t("已归档"))}</summary>${chats.filter(c => c.archived).map(row).join('')}</details>`;
  } else {
    $('history').innerHTML = state.projects.filter(p => p.name.toLowerCase().includes(query) || state.conversations.some(c => c.projectId === p.id && matches(c))).map(p => {
      const current = p.id === state.projectId || p.workspaceUri === state.workspace.uri;
      const open = query || (expandedProjects.has(p.id) ? expandedProjects.get(p.id) : current);
      return `<details data-workspace-group="${p.id}" class="workspace-group history-section ${current ? 'current' : ''}" ${open ? 'open' : ''}><summary>${icon('folder')}<span title="${esc(p.name)}">${esc(p.name)}${p.archived ? t(" · 已归档") : ''}</span><button data-project-menu="${p.id}" class="history-menu" aria-label="${esc(t("项目操作："))}${esc(p.name)}">⋯</button></summary><div class="workspace-chats">${!p.archived ? `<div class="history-row"><button data-project-new="${p.id}" class="history-item">＋ ${esc(t("新建项目会话"))}</button></div>` : ''}${state.conversations.filter(c => c.projectId === p.id && !c.archived && matches(c)).map(row).join('')}<details class="archive-list"><summary class="section-title">${esc(t("已归档会话"))}</summary>${state.conversations.filter(c => c.projectId === p.id && c.archived && matches(c)).map(row).join('')}</details></div></details>`;
    }).join('') || `<p class="history-empty">${esc(t("还没有项目。点击工作区旁的文件夹按钮，新建一个项目。"))}</p>`;
  }
}
function renderMessages() { richContent.update(state); messagesRenderer.render(state); }
function renderInteraction() {
  const fingerprint = JSON.stringify([state.approval,state.question]); if (lastInteraction === fingerprint) return; lastInteraction = fingerprint;
  const p = state.approval, q = state.question;
  $('interaction').innerHTML = (p ? `<section class="approval"><h3>${esc(t("等待命令同意 ·"))} ${esc(p.tool)}</h3><p>${esc(p.summary)}</p><small>${esc(p.risk)}</small><details><summary>${esc(t("调用参数"))}</summary><pre>${esc(p.arguments)}</pre></details><input id="approval-feedback" type="text" placeholder="${esc(t("给 Agent 的反馈（可选）"))}"><div class="buttons"><button class="primary" data-approve="${p.id}">${esc(t("同意"))}</button><button class="danger" data-reject="${p.id}">${esc(t("拒绝"))}</button><label class="check"><input id="remember-approval" type="checkbox">${esc(t("本会话后续自动同意"))}</label></div></section>` : '') +
    (q ? `<section class="approval"><h3>${esc(q.title)}</h3><p>${esc(q.question)}</p>${q.options.map(o => `<label class="check"><input class="question-option" type="checkbox" value="${esc(o)}">${esc(o)}</label>`).join('')}<input id="question-text" type="text" placeholder="${esc(t("补充说明"))}"><button class="primary" data-answer="${q.id}">${esc(t("提交回答"))}</button></section>` : '');
}
let dialogRefresh = null;
function dialog(title, html, refresh = null) { dialogRefresh = refresh; $('dialog-title').textContent = title; $('dialog-content').innerHTML = html; if (!$('dialog').open) $('dialog').showModal(); }
function renderSettings() {
  document.querySelectorAll('#settings-tabs button').forEach(b => b.classList.toggle('active',b.dataset.tab === tab));
  $('settings-page-title').textContent = t(settingPages[tab][0]); $('settings-page-description').textContent = t(settingPages[tab][1]);
  const el = $('settings-content');
  if (tab === 'prompts') el.innerHTML = `<div class="settings-toolbar"><label class="check"><input id="pure-prompt" class="switch" role="switch" type="checkbox" ${state.purePrompt ? 'checked' : ''}>${esc(t("纯提示词模式"))}</label><button data-add-prompt class="tonal">＋ ${esc(t("新建提示词"))}</button></div><div class="settings-grid">` + state.prompts.map(p => `<section class="settings-card ${p.id === state.promptId ? 'selected' : ''}"><div class="card-title"><h3>${esc(p.name)}</h3>${p.id === state.promptId ? `<span class="status-chip">✓ ${esc(t("已选中"))}</span>` : ''}</div><p class="description">${esc(p.prompt.slice(0,180)) || t("使用应用原生提示词")}</p><div class="buttons"><button data-prompt-select="${esc(p.id)}" ${p.id === state.promptId ? 'disabled' : ''}>${esc(t("使用"))}</button>${!p.builtIn ? `<button data-edit-prompt="${esc(p.id)}">${esc(t("编辑"))}</button><button data-delete-prompt="${esc(p.id)}" class="danger">${esc(t("删除"))}</button>` : ''}</div></section>`).join('') + '</div>';
  if (tab === 'agents') el.innerHTML = `<div class="settings-toolbar"><label class="check"><input id="agents-enabled" class="switch" role="switch" type="checkbox" ${state.subAgentsEnabled ? 'checked' : ''}>${esc(t("子代理编排"))}</label><button data-add-agent class="tonal">＋ ${esc(t("新建子代理"))}</button></div><div class="settings-grid">` + state.subAgents.map(a => `<section class="settings-card"><div class="card-title"><h3>${esc(a.name)}</h3><input data-agent-enabled="${esc(a.id)}" class="switch" role="switch" aria-label="${esc(t("启用"))} ${esc(a.name)}" type="checkbox" ${a.enabled ? 'checked' : ''}></div><p>${esc(a.model)}</p><p class="description">${esc(a.description)}</p><div class="buttons"><button data-edit-agent="${esc(a.id)}">${esc(t("编辑"))}</button><button data-delete-agent="${esc(a.id)}" class="danger">${esc(t("删除"))}</button></div></section>`).join('') + '</div>';
  if (tab === 'tools') el.innerHTML = `<div class="settings-toolbar"><input id="tools-search" type="search" placeholder="${esc(t("搜索工具名称 / 描述"))}" aria-label="${esc(t("搜索 Agent Tools"))}"></div><p id="tools-summary" class="settings-intro"></p><div id="tools-list"></div>`;
  if (tab === 'tools') renderTools();
  if (tab === 'mcp') el.innerHTML = `<div class="settings-toolbar"><span class="muted">${state.mcpServers.length} ${esc(t("个服务器"))}</span><button data-add-mcp class="tonal">＋ ${esc(t("添加服务器"))}</button></div><div class="settings-grid">` + state.mcpServers.map(s => `<section class="settings-card"><div class="card-title"><h3>${esc(s.name)}</h3><input data-mcp-enabled="${esc(s.id)}" class="switch" role="switch" aria-label="${esc(t("启用"))} ${esc(s.name)}" type="checkbox" ${s.enabled ? 'checked' : ''}></div><p>${esc(s.url)}<br>${s.tools.length} ${esc(t("个工具 ·"))} ${s.timeoutSeconds} ${esc(t("秒超时"))}</p><div class="buttons"><button data-edit-mcp="${esc(s.id)}">${esc(t("编辑"))}</button><button data-refresh-mcp="${esc(s.id)}">${esc(t("连接 / 刷新"))}</button><button data-delete-mcp="${esc(s.id)}" class="danger">${esc(t("删除"))}</button></div><details><summary>${esc(t("工具列表"))}</summary>${s.tools.map(tool => `<p><strong>${esc(tool.name)}</strong><br>${esc(tool.description)}</p><pre>${esc(tool.inputSchema)}</pre>`).join('') || t("尚未获取工具")}</details></section>`).join('') + '</div>';
  if (tab === 'compression') {
    const c = state.compression;
    el.innerHTML = `<div class="metrics"><div class="metric"><strong id="metric-tokens"></strong><small>${esc(t("估算上下文 tokens"))}</small></div><div class="metric"><strong id="metric-turns"></strong><small>${esc(t("压缩后的对话轮次"))}</small></div></div><p id="metric-details" class="settings-intro"></p><button data-refresh-context class="quiet">↻ ${esc(t("刷新用量"))}</button><form id="compression-form" class="form compression-form"><label>${esc(t("自动压缩"))}<select name="mode">${choices([['off',t("关闭自动压缩")],['turns',t("按对话轮次")],['tokens',t("按 tokens 用量")]],c.mode)}</select></label><label>${esc(t("轮次阈值"))}<input name="turns" type="number" min="1" max="10000" value="${c.turns}" required></label><label>Token ${esc(t("阈值"))}<input name="tokens" type="number" min="1024" max="16777216" value="${c.tokens}" required></label><label>${esc(t("压缩模型服务商"))}<select name="profileId">${choices([['',t("跟随当前会话")]],c.profileId)}${profileOptions(c.profileId)}</select></label><label>${esc(t("压缩模型"))}<input name="model" value="${esc(c.model)}" placeholder="${esc(t("留空跟随当前会话"))}"></label><label>${esc(t("分块数量"))}<input name="chunks" type="number" min="${c.minChunks}" max="${c.maxChunks}" value="${c.chunks}" required></label><button class="primary" type="submit">${esc(t("保存压缩设置"))}</button></form><section class="settings-card manual-compression"><form id="compress-form" class="form"><label>${esc(t("本次压缩指令"))}<textarea name="instruction" placeholder="${esc(t("需要保留的重点（可选）"))}"></textarea></label><button id="compress-now" type="submit" ${state.running || !state.conversationId ? 'disabled' : ''}>${esc(t("立即压缩历史"))}</button></form></section>`;
    updateMetrics();
  }
}
function renderTools() {
  const q = ($('tools-search')?.value || '').toLowerCase();
  const opened = new Set([...$('tools-list').querySelectorAll('details[open]')].map(d => d.dataset.toolDetail));
  $('tools-list').innerHTML = state.tools.filter(tool => `${tool.name} ${tool.title} ${tool.description}`.toLowerCase().includes(q)).map(tool => `<details class="settings-card tool-card" data-tool-detail="${esc(tool.name)}" ${opened.has(tool.name) ? 'open' : ''}><summary><div class="tool-heading"><div><h3>${esc(tool.title)}</h3><span class="tool-name">${esc(tool.name)}</span></div><span class="tool-state ${tool.enabled ? 'enabled' : ''}">${tool.locked ? t("始终启用") : tool.enabled ? t("已启用") : t("已停用")}</span><input type="checkbox" class="switch" role="switch" data-tool="${esc(tool.name)}" ${tool.enabled ? 'checked' : ''} ${tool.locked ? 'disabled' : ''} aria-label="${esc(t("启用"))} ${esc(tool.name)}">${icon('down')}</div></summary><p>${esc(tool.description)}</p></details>`).join('') || `<p class="history-empty">${esc(t("没有匹配的工具"))}</p>`;
  updateToolStates();
}
function updateToolStates() {
  $('tools-summary').textContent = `${state.tools.length} ${t("个工具 ·")} ${state.tools.filter(tool => tool.enabled).length} ${t("个已启用")}`;
  for (const input of $('tools-list').querySelectorAll('input[data-tool]')) {
    const tool = state.tools.find(item => item.name === input.dataset.tool); if (!tool) continue;
    input.checked = tool.enabled;
    const label = input.parentElement.querySelector('.tool-state');
    label.textContent = tool.locked ? t("始终启用") : tool.enabled ? t("已启用") : t("已停用"); label.classList.toggle('enabled',tool.enabled);
  }
}
function updateMetrics() { if ($('metric-tokens')) { $('metric-tokens').textContent = state.context.tokens.toLocaleString(); $('metric-turns').textContent = state.context.turns; $('metric-details').textContent = t("{count} 条上下文消息{summary}。用量为估算值。",{count:state.context.messages,summary:state.context.compressed ? t(" · 已包含压缩摘要") : ''}); $('compress-now').disabled = state.running || !state.conversationId; } }
function openSettings(nextTab) { tab = nextTab || tab; $('settings').hidden = false; settingsFingerprint = ''; renderSettings(); if (!$('settings').open) $('settings').showModal(); }
function editPrompt(id) { const p = state.prompts.find(x => x.id === id) || {id:'',name:'',prompt:'',exampleConversation:''}; dialog(id ? t("编辑提示词") : t("新建提示词"), `<form id="prompt-form" class="form"><input name="id" value="${esc(p.id)}" type="hidden"><label>${esc(t("名称"))}<input name="name" value="${esc(p.name)}" required></label><label>${esc(t("提示词"))}<textarea name="prompt">${esc(p.prompt)}</textarea></label><label>${esc(t("示例对话"))}<textarea name="exampleConversation">${esc(p.exampleConversation)}</textarea></label><button class="primary">${esc(t("保存"))}</button></form>`, () => editPrompt(id)); }
function editAgent(id) { const a = state.subAgents.find(x => x.id === id) || {id:'',name:'',profileId:state.profileId,model:state.model,description:'',enabled:true}; dialog(id ? t("编辑子代理") : t("新建子代理"), `<form id="agent-form" class="form"><input name="id" value="${esc(a.id)}" type="hidden"><label>${esc(t("名称"))}<input name="name" value="${esc(a.name)}" required></label><label>${esc(t("服务商"))}<select name="profileId">${profileOptions(a.profileId)}</select></label><label>${esc(t("模型"))}<input name="model" value="${esc(a.model)}" required></label><label>${esc(t("职责描述"))}<textarea name="description">${esc(a.description)}</textarea></label><label class="check"><input name="enabled" type="checkbox" ${a.enabled ? 'checked' : ''}>${esc(t("启用"))}</label><button class="primary">${esc(t("保存"))}</button></form>`, () => editAgent(id)); }
function editMcp(id) { const s = state.mcpServers.find(x => x.id === id) || {id:'',name:'',rawJson:'{\n  "url": "https://example.com/mcp",\n  "type": "streamableHttp"\n}',timeoutSeconds:60,enabled:true}; dialog(id ? t("编辑 MCP 服务器") : t("添加 MCP 服务器"), `<form id="mcp-form" class="form"><input name="id" value="${esc(s.id)}" type="hidden"><label>${esc(t("名称"))}<input name="name" value="${esc(s.name)}" required></label><label>${esc(t("连接配置 JSON"))}<textarea name="rawJson" rows="10" required spellcheck="false">${esc(s.rawJson)}</textarea></label><label>${esc(t("超时（秒）"))}<input name="timeoutSeconds" type="number" min="5" max="600" value="${s.timeoutSeconds}" required></label><label class="check"><input name="enabled" type="checkbox" ${s.enabled ? 'checked' : ''}>${esc(t("启用"))}</label><button class="primary">${esc(t("保存"))}</button></form>`, () => editMcp(id)); }
async function browseWorkspace(uri = '', createProject = false, back = false) {
  const result = await action('browseWorkspace',{uri},false); workspaces = result.entries; needsStoragePermission = !!result.needsStoragePermission;
  if (!back) workspaceStack.push(uri);
  showWorkspaceDialog(uri,createProject);
}
function showWorkspaceDialog(uri,createProject) {
  dialog(createProject ? t("选择目录，新建项目") : t("选择工作目录"), `<p class="settings-intro">${esc(t("直接浏览手机共享存储与 PRoot Linux，无需 SAF 目录选择器。"))}${needsStoragePermission ? `<br><strong class="rich-error">${esc(t("请先在手机授予管理所有文件权限"))}</strong>` : ''}</p><div class="workspace-list">${uri ? `<button data-workspace-back data-create="${createProject}">← ${esc(t("返回上一级"))}</button><p class="workspace-path">${esc(uri.startsWith('file:') ? decodeURI(uri.slice(7)) : uri)}</p><button class="primary" data-workspace-use="${esc(uri)}" data-create="${createProject}">${esc(t("选择此目录"))}</button>` : ''}${workspaces.map(w => `<div class="workspace-row"><button ${w.browsable ? 'data-workspace-browse' : 'data-workspace-use'}="${esc(w.uri)}" data-create="${createProject}">${esc(w.name)}</button><button data-workspace-use="${esc(w.uri)}" data-create="${createProject}">${esc(t("选择"))}</button></div>`).join('') || `<p class="muted">${esc(t("没有可用目录。请授予管理所有文件权限或安装 PRoot Linux。"))}</p>`}</div>`, () => showWorkspaceDialog(uri,createProject));
}
function copyContent(content) {
  dialog(t('复制内容'),`<textarea class="copy-area" rows="12">${esc(content)}</textarea>`,() => copyContent(content));
  $('dialog-content').querySelector('textarea').select();
}
function editMessage(id) {
  const m=state.messages.find(m => m.id===Number(id)); if (!m) return;
  dialog(t('编辑并重新生成'),`<form id="edit-message-form" class="form"><input name="id" value="${m.id}" type="hidden"><textarea name="text">${esc(m.content)}</textarea><button class="primary" data-regenerate="${m.id}" type="button">${esc(t('保存并重新生成'))}</button></form>`,() => editMessage(id));
}
function chatMenu(id, project = false) {
  const c = (project ? state.projects : state.conversations).find(x => x.id === Number(id)); if (!c) return;
  dialog(project ? c.name : c.title, `<div class="form"><label>${project ? t("项目名称") : t("会话标题")}<input id="rename-value" value="${esc(project ? c.name : c.title)}"></label><div class="buttons"><button data-rename="${id}" data-project="${project}">${esc(t("保存名称"))}</button><button data-pin="${id}" data-project="${project}" data-enabled="${!c.pinned}">${c.pinned ? t("取消置顶") : t("置顶")}</button><button data-archive="${id}" data-project="${project}" data-restore="${c.archived}">${c.archived ? t("恢复") : t("归档")}</button></div></div>`, () => chatMenu(id,project));
}
async function send() {
  const deliveryEnabled = !!state?.composerDelivery?.enabled;
  if (!state || (state.running && !deliveryEnabled) || busy || uploading || !connected) return;
  const text = $('input').value; if (!text.trim() && !state.uploads.length) return;
  busy = true; render();
  try {
    const result = await action('send',{text});
    $('input').value = ''; resizeInput();
    if (result?.delivery === 'queued') message(t("已排队，等待当前回复结束"));
  }
  catch (e) { message(e.message); }
  finally { busy = false; render(); }
}
async function uploadFiles(files) {
  const deliveryEnabled = !!state?.composerDelivery?.enabled;
  if (uploading || !state || (state.running && !deliveryEnabled)) return;
  uploading = true; const target = session(); render();
  try {
    for (const file of files) {
      if (file.size > 8*1024*1024) { message(`${file.name} ${t("超过 8 MB")}`); continue; }
      $('upload-status').textContent = `${t("正在上传")} ${file.name}…`;
      await api('/api/upload', {method:'POST',headers:{'Content-Type':file.type || 'application/octet-stream','X-File-Name':encodeURIComponent(file.name),'X-Conversation-Id':String(target.conversationId),'X-Project-Id':String(target.projectId),'X-Session-Revision':String(target.sessionRevision)},body:file});
    }
    await sync();
  } catch (e) { message(e.message); }
  finally { uploading = false; $('upload-status').textContent = ''; $('file-input').value = ''; render(); }
}
$('login-form').addEventListener('submit', e => { e.preventDefault(); token = $('token').value.trim(); safe(async () => { await sync(); if (connected) { sessionStorage.setItem('lyra-web-token',token); $('token').value = ''; } }); });
$('logout').addEventListener('click', () => { sessionStorage.removeItem('lyra-web-token'); token = ''; state = null; showLogin(); });
$('new-chat').addEventListener('click', () => safe(async () => { await action('new'); selectHistory('normal'); closeSidebar(); }));
$('history-search').addEventListener('input',renderHistory);
function selectHistory(next) {
  historyTab = next;
  for (const kind of ['normal','project']) { const button = $(`${kind}-history`); button.classList.toggle('active',next === kind); button.setAttribute('aria-pressed',String(next === kind)); }
  renderHistory();
}
$('normal-history').addEventListener('click',() => selectHistory('normal'));
$('project-history').addEventListener('click',() => selectHistory('project'));
$('settings-button').addEventListener('click',() => { closeSidebar(); openSettings(); });
$('prompt-button').addEventListener('click',() => openSettings('prompts'));
$('close-settings').addEventListener('click',() => $('settings').close());
$('settings').addEventListener('close',() => $('settings').hidden = true);
for (const id of ['settings','dialog','reasoning-dialog']) $(id).addEventListener('click', e => {
  if (e.target !== $(id)) return;
  const rect = $(id).getBoundingClientRect();
  if (e.clientX < rect.left || e.clientX > rect.right || e.clientY < rect.top || e.clientY > rect.bottom) $(id).close();
});
function closeSidebar() { $('app').classList.remove('sidebar-open'); $('sidebar-scrim').hidden = true; }
$('collapse-sidebar').addEventListener('click',() => { closeSidebar(); $('app').classList.add('sidebar-collapsed'); $('open-sidebar').hidden = false; });
$('open-sidebar').addEventListener('click',() => { $('app').classList.remove('sidebar-collapsed'); $('open-sidebar').hidden = true; if (matchMedia('(max-width:760px)').matches) { $('app').classList.add('sidebar-open'); $('sidebar-scrim').hidden = false; } });
$('sidebar-scrim').addEventListener('click',closeSidebar);
$('search-toggle').addEventListener('click',() => { $('history-search-field').hidden = !$('history-search-field').hidden; if (!$('history-search-field').hidden) $('history-search').focus(); });
$('history').addEventListener('toggle',e => { if (e.target.dataset.workspaceGroup) expandedProjects.set(Number(e.target.dataset.workspaceGroup),e.target.open); },true);
$('composer-more').addEventListener('click',() => { $('composer-menu').hidden = !$('composer-menu').hidden; $('composer-more').setAttribute('aria-expanded',String(!$('composer-menu').hidden)); });
document.addEventListener('click',e => { if (!e.target.closest('.composer-menu-anchor') || e.target.closest('[role=menuitem]')) { $('composer-menu').hidden = true; $('composer-more').setAttribute('aria-expanded','false'); } });
$('context-button').addEventListener('click',() => { openSettings('compression'); safe(() => action('refreshContext')); });
$('close-dialog').addEventListener('click',() => $('dialog').close());
$('workspace').addEventListener('click',() => { workspaceStack=[]; safe(() => browseWorkspace()); });
$('model-select').addEventListener('change', e => { const [profileId,model]=e.target.value.split('\n'); safe(() => action('model',{profileId,model})); });
$('auto-approval').addEventListener('change', e => safe(() => action('autoApproval',{enabled:e.target.checked})));
function resizeInput() { $('input').style.height = 'auto'; $('input').style.height = `${Math.min(250,Math.max(104,$('input').scrollHeight))}px`; }
$('input').addEventListener('input', () => { resizeInput(); if (state) render(); });
$('input').addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); send(); } });
$('send').addEventListener('click',send); $('stop').addEventListener('click',() => safe(() => action('stop'))); $('continue').addEventListener('click',() => safe(() => action('continue')));
$('attach').addEventListener('click',() => $('file-input').click()); $('file-input').addEventListener('change',e => uploadFiles([...e.target.files]));
for (const name of ['dragenter','dragover']) $('drop-zone').addEventListener(name,e => { e.preventDefault(); $('drop-zone').classList.add('dragging'); });
for (const name of ['dragleave','drop']) $('drop-zone').addEventListener(name,e => { e.preventDefault(); $('drop-zone').classList.remove('dragging'); if (name === 'drop') uploadFiles([...e.dataTransfer.files]); });
document.addEventListener('keydown',e => {
  if (e.key === 'Escape') { $('composer-menu').hidden = true; $('composer-more').setAttribute('aria-expanded','false'); closeSidebar(); }
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k' && state && !$('settings').open && !$('dialog').open) { e.preventDefault(); safe(async () => { await action('new'); selectHistory('normal'); closeSidebar(); }); }
});
document.addEventListener('input',e => { if (e.target.id === 'tools-search') renderTools(); });
document.addEventListener('change',e => safe(async () => {
  const el=e.target,d=el.dataset;
  if (el.id === 'pure-prompt') await action('purePrompt',{enabled:el.checked});
  if (el.id === 'agents-enabled') await action('subAgentsEnabled',{enabled:el.checked});
  if (d.agentEnabled) await action('subAgentSave',{...state.subAgents.find(a => a.id === d.agentEnabled),enabled:el.checked});
  if (d.mcpEnabled) await action('mcpEnabled',{id:d.mcpEnabled,enabled:el.checked});
  if (d.tool) await action('tool',{name:d.tool,enabled:el.checked});
}));
document.addEventListener('submit',e => {
  if (e.target.id === 'login-form') return;
  e.preventDefault(); const form=e.target, formId=form.getAttribute('id'), args=Object.fromEntries(new FormData(form));
  safe(async () => {
    const button = form.querySelector('button[type=submit],button.primary,button'); if (button) button.disabled = true;
    try {
      if (formId === 'prompt-form') await action('promptSave',args);
      if (formId === 'agent-form') await action('subAgentSave',{...args,enabled:form.elements.enabled.checked});
      if (formId === 'mcp-form') { JSON.parse(args.rawJson); await action('mcpSave',{...args,enabled:form.elements.enabled.checked,timeoutSeconds:Number(args.timeoutSeconds)}); }
      if (formId === 'compression-form') await action('compression',{...args,turns:Number(args.turns),tokens:Number(args.tokens),chunks:Number(args.chunks)});
      if (formId === 'compress-form') await action('compress',args);
      if (['prompt-form','agent-form','mcp-form'].includes(formId)) $('dialog').close();
      message(formId === 'compress-form' ? t("已开始压缩历史") : t("已同步到手机")); settingsFingerprint=''; renderSettings();
    } finally { if (button) button.disabled = false; }
  });
});
document.addEventListener('click',e => safe(async () => {
  const el=e.target.closest('button'); if (!el) return; const d=el.dataset;
  if (el.closest('.workspace-group>summary')) e.preventDefault();
  if (d.tab) { tab=d.tab; settingsFingerprint=''; renderSettings(); $('settings-content').parentElement.scrollTop=0; }
  if (d.openSettings) openSettings(d.openSettings);
  if ('attach' in d) $('file-input').click();
  if (d.select) { await action('select',{id:Number(d.select)}); closeSidebar(); }
  if (d.projectNew) { await action('newProjectChat',{id:Number(d.projectNew)}); closeSidebar(); }
  if (d.chatMenu) chatMenu(d.chatMenu); if (d.projectMenu) chatMenu(d.projectMenu,true);
  if ('createProject' in d) { workspaceStack=[]; await browseWorkspace('',true); }
  if (d.workspaceBrowse) await browseWorkspace(d.workspaceBrowse,d.create==='true');
  if ('workspaceBack' in d) { workspaceStack.pop(); await browseWorkspace(workspaceStack.at(-1) || '',d.create==='true',true); }
  if (d.workspaceUse) { await action(d.create==='true' ? 'projectCreate' : 'workspace',{uri:d.workspaceUse}); $('dialog').close(); }
  if (d.removeUpload) await action('removeUpload',{uri:d.removeUpload});
  if (d.starter) { $('input').value=d.starter; $('input').focus(); render(); }
  if (d.approve || d.reject) await action('approval',{id:Number(d.approve || d.reject),approved:!!d.approve,remember:$('remember-approval').checked,feedback:$('approval-feedback').value});
  if (d.answer) await action('question',{id:Number(d.answer),options:[...document.querySelectorAll('.question-option:checked')].map(o => o.value),text:$('question-text').value});
  if (d.copy !== undefined) { const content=state.messages[Number(d.copy)].content; try { await navigator.clipboard.writeText(content); message(t("已复制")); } catch { copyContent(content); } }
  if (d.branch) await action('branch',{id:Number(d.branch)});
  if (d.editMessage) editMessage(d.editMessage);
  if (d.regenerate) { await action('editMessage',{id:Number(d.regenerate),text:$('edit-message-form').elements.text.value}); $('dialog').close(); }
  if (d.rename) { const value=$('rename-value').value; await action(d.project==='true' ? 'projectRename' : 'rename',{id:Number(d.rename),title:value,name:value}); $('dialog').close(); }
  if (d.pin) { await action(d.project==='true' ? 'projectPin' : 'pin',{id:Number(d.pin),enabled:d.enabled==='true'}); $('dialog').close(); }
  if (d.archive) { await action(d.project==='true' ? (d.restore==='true' ? 'projectRestore' : 'projectArchive') : (d.restore==='true' ? 'restore' : 'archive'),{id:Number(d.archive)}); $('dialog').close(); }
  if ('addPrompt' in d) editPrompt(); if (d.editPrompt) editPrompt(d.editPrompt); if (d.promptSelect) await action('promptSelect',{id:d.promptSelect});
  if ('addAgent' in d) editAgent(); if (d.editAgent) editAgent(d.editAgent);
  if ('addMcp' in d) editMcp(); if (d.editMcp) editMcp(d.editMcp);
  if ('refreshContext' in d) await action('refreshContext');
  if (d.refreshMcp) { el.disabled=true; try { await action('mcpRefresh',{id:d.refreshMcp}); message(t("工具列表已同步")); } finally { el.disabled=false; } }
  const deletion = d.deletePrompt ? ['promptDelete',d.deletePrompt] : d.deleteAgent ? ['subAgentDelete',d.deleteAgent] : d.deleteMcp ? ['mcpDelete',d.deleteMcp] : null;
  if (deletion && confirm(t("确定删除此配置？"))) await action(deletion[0],{id:deletion[1]});
}));
localizeStatic();
const richContent = createRichContent();
const messagesRenderer = createLiveMessages($('messages'),richContent);
let lastVersion = -1, serverInstance = '';
function acceptState(next) {
  if (next.instanceId && serverInstance===next.instanceId && next.version<=lastVersion) return;
  if (next.instanceId !== serverInstance) {serverInstance=next.instanceId || '';lastVersion=-1;}
  lastVersion=next.version ?? lastVersion+1;
  if(next.partial && state && state.instanceId===next.instanceId) {
    const records=new Map(state.messages.map(record=>[record.id,record]));
    for(const record of next.messages || [])records.set(record.id,record);
    next={...state,...next,messages:(next.messageOrder || state.messages.map(record=>record.id)).map(id=>records.get(id)).filter(Boolean)};
  }
  if (state && state.sessionRevision!==next.sessionRevision) {drafts.set(state.sessionRevision,$('input').value);$('input').value=drafts.get(next.sessionRevision) || '';resizeInput();}
  state=next;applyLocale(next.locale);connected=true;$('app').hidden=false;$('login').hidden=true;
  $('connection').classList.remove('offline');
  const connectionHtml=`<i></i> ${esc(t('已连接手机'))}`;
  if($('connection').innerHTML!==connectionHtml)$('connection').innerHTML=connectionHtml;
  scheduleStateRender();
}
const stateStream = createStateStream({getToken:()=>token,accept:acceptState,unauthorized:error=>{sessionStorage.removeItem('lyra-web-token');token='';showLogin(t(error));}});
const reasoningControl = createReasoningControl({commit:value => action('reasoning',{value}),fail:error => message(error.message)});
safe(async () => { await syncLoginLocale(); if (token) await sync(); }); poll();
