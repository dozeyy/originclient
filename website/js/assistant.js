// Public chat UI. Anthropic credentials and the system prompt live in the private Cloudflare Worker.
const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const PLANS = [
  { name: 'Free', price: '$0', allowance: '$1 AI usage / month' },
  { name: 'Plus', price: '$5', allowance: '$4 AI usage / month', featured: true },
  { name: 'Pro', price: '$10', allowance: '$8 AI usage / month' },
  { name: 'Power', price: '$20', allowance: '$16 AI usage / month' },
  { name: 'Studio', price: '$100', allowance: '$80 AI usage / month' },
];
const MANIFEST_URL = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';
const MAX_FILE_BYTES = 20 * 1024 * 1024;
const MAX_LOG_CHARS = 60_000;
const SUPPORTED_TEXT_FILE = /\.(?:log|txt|json|toml|properties|cfg|conf|ini|java|kt|gradle|kts|groovy|xml|ya?ml|patch|diff|md|csv|mcmeta|accesswidener)$/i;
const CHAT_ENDPOINT = 'https://origin-assistant-api.will-f11.workers.dev/v1/chat';
const FEEDBACK_ENDPOINT = 'https://origin-assistant-api.will-f11.workers.dev/v1/feedback';
const FEEDBACK_STATS_ENDPOINT = 'https://origin-assistant-api.will-f11.workers.dev/v1/feedback/stats';
const USAGE_ENDPOINT = 'https://origin-assistant-api.will-f11.workers.dev/v1/usage';
const OWNER_ENDPOINT = 'https://origin-assistant-api.will-f11.workers.dev/v1/owner/unlock';
const OWNER_TOKEN_KEY = 'origin-assistant-owner-session';
let ownerToken = '';
let attachedFile = null;
let conversation = [];
let minecraftVersions = [];

function renderPlans(target) {
  target.innerHTML = PLANS.map(plan => `
    <article class="plan-option${plan.featured ? ' plan-option--featured' : ''}">
      <b>${plan.name}</b><strong>${plan.price}<span aria-hidden="true"> / mo</span></strong>
      <small>Up to ${plan.allowance}</small>
      ${target.matches('[data-upgrade-options]') ? '<button type="button" disabled>Coming soon</button>' : ''}
    </article>`).join('');
}

function updateUsage(usage) {
  if (usage?.unlimited) {
    $('[data-usage-title]').textContent = 'Owner access';
    $('[data-usage-label]').textContent = 'Unlimited';
    $('[data-usage-remaining]').textContent = 'Unlimited';
    $('[data-usage-reset]').textContent = 'No monthly cap';
    const meter = $('[data-usage-meter]');
    meter.setAttribute('aria-label', 'Owner access is unlimited');
    meter.setAttribute('aria-valuemax', '1');
    meter.setAttribute('aria-valuenow', '0');
    meter.querySelector('span').style.width = '0%';
    $('[data-owner-status]').dataset.state = 'success';
    $('[data-owner-status]').textContent = 'Owner access is active in this browser tab.';
    return;
  }
  if (!usage || !Number.isFinite(Number(usage.spentUsd)) || !Number.isFinite(Number(usage.limitUsd))) return;
  const spent = Math.max(0, Number(usage.spentUsd));
  const limit = Math.max(0, Number(usage.limitUsd));
  $('[data-usage-title]').textContent = 'Free plan usage';
  $('[data-usage-label]').textContent = `$${spent.toFixed(2)} / $${limit.toFixed(2)}`;
  const remaining = Number.isFinite(Number(usage.remainingUsd)) ? Math.max(0, Number(usage.remainingUsd)) : Math.max(0, limit - spent);
  $('[data-usage-remaining]').textContent = `$${remaining.toFixed(2)}`;
  $('[data-usage-reset]').textContent = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' }).format(new Date(Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth() + 1, 1)));
  const meter = $('[data-usage-meter]');
  meter.setAttribute('aria-label', 'Monthly AI usage');
  const percentage = limit > 0 ? Math.min(100, (spent / limit) * 100) : 0;
  meter.setAttribute('aria-valuemax', String(limit));
  meter.setAttribute('aria-valuenow', String(Math.min(spent, limit)));
  meter.querySelector('span').style.width = `${percentage}%`;
}

async function loadUsage() {
  try {
    const headers = ownerToken ? { authorization: `Bearer ${ownerToken}` } : {};
    const response = await fetch(USAGE_ENDPOINT, { cache: 'no-store', headers });
    if (!response.ok) return;
    const result = await response.json();
    if (ownerToken && !result.usage?.unlimited) {
      ownerToken = '';
      sessionStorage.removeItem(OWNER_TOKEN_KEY);
      $('[data-owner-status]').dataset.state = 'error';
      $('[data-owner-status]').textContent = 'Owner access expired. Enter your code again.';
    }
    updateUsage(result.usage);
    if (ownerToken) void loadFeedbackStats();
  } catch {
    // The chat reports a clear error when the service is unavailable.
  }
}

async function loadFeedbackStats() {
  if (!ownerToken) return;
  try {
    const response = await fetch(FEEDBACK_STATS_ENDPOINT, {
      cache: 'no-store',
      headers: { authorization: `Bearer ${ownerToken}` },
    });
    if (!response.ok) return;
    const result = await response.json();
    $('[data-feedback-helpful]').textContent = Number(result.votes?.helpful ?? 0).toLocaleString();
    $('[data-feedback-not-helpful]').textContent = Number(result.votes?.notHelpful ?? 0).toLocaleString();
    $('[data-feedback-section]').hidden = false;
  } catch {
    // Owner feedback totals are optional settings content.
  }
}

function appendMessage(chat, role, text, extraClass = '') {
  chat.querySelector('.welcome')?.remove();
  const message = document.createElement('div');
  message.className = `chat-message chat-message--${role}${extraClass ? ` ${extraClass}` : ''}`;
  message.textContent = text;
  chat.append(message);
  chat.classList.add('has-messages');
  chat.scrollTop = chat.scrollHeight;
  return message;
}

function appendInlineMarkdown(parent, text) {
  const pattern = /\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)|`([^`\n]+)`|\*\*([^*\n]+)\*\*|__([^_\n]+)__|\*([^*\n]+)\*|_([^_\n]+)_/g;
  let cursor = 0;
  for (const match of text.matchAll(pattern)) {
    const index = match.index ?? 0;
    if (index > cursor) parent.append(document.createTextNode(text.slice(cursor, index)));
    if (match[1] !== undefined) {
      try {
        const url = new URL(match[2]);
        if (url.protocol === 'http:' || url.protocol === 'https:') {
          const link = document.createElement('a');
          link.href = url.href;
          link.target = '_blank';
          link.rel = 'noopener noreferrer';
          link.textContent = match[1];
          parent.append(link);
        } else {
          parent.append(document.createTextNode(match[0]));
        }
      } catch {
        parent.append(document.createTextNode(match[0]));
      }
    } else if (match[3] !== undefined) {
      const code = document.createElement('code');
      code.textContent = match[3];
      parent.append(code);
    } else if (match[4] !== undefined || match[5] !== undefined) {
      const strong = document.createElement('strong');
      strong.textContent = match[4] ?? match[5];
      parent.append(strong);
    } else {
      const emphasis = document.createElement('em');
      emphasis.textContent = match[6] ?? match[7];
      parent.append(emphasis);
    }
    cursor = index + match[0].length;
  }
  if (cursor < text.length) parent.append(document.createTextNode(text.slice(cursor)));
}

function renderMarkdown(target, markdown) {
  target.replaceChildren();
  const lines = String(markdown).replace(/\r\n?/g, '\n').split('\n');
  let index = 0;
  const isBlockStart = line => /^\s{0,3}(?:#{1,6}\s|```|~~~|>|[-+*]\s+|\d+[.)]\s+|(?:-{3,}|\*{3,}|_{3,})\s*$)/.test(line);

  while (index < lines.length) {
    const line = lines[index];
    if (!line.trim()) { index++; continue; }

    const fence = line.match(/^\s{0,3}(`{3,}|~{3,})\s*([\w+-]*)\s*$/);
    if (fence) {
      const codeLines = [];
      index++;
      while (index < lines.length && !new RegExp(`^\\s{0,3}${fence[1][0]}{${fence[1].length},}\\s*$`).test(lines[index])) {
        codeLines.push(lines[index++]);
      }
      if (index < lines.length) index++;
      const pre = document.createElement('pre');
      const code = document.createElement('code');
      if (fence[2]) code.dataset.language = fence[2].toLowerCase();
      code.textContent = codeLines.join('\n');
      pre.append(code);
      target.append(pre);
      continue;
    }

    const heading = line.match(/^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$/);
    if (heading) {
      const element = document.createElement(`h${heading[1].length}`);
      appendInlineMarkdown(element, heading[2]);
      target.append(element);
      index++;
      continue;
    }

    if (/^\s{0,3}(?:-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      target.append(document.createElement('hr'));
      index++;
      continue;
    }

    const tableSeparator = lines[index + 1]?.match(/^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$/);
    if (line.includes('|') && tableSeparator) {
      const splitCells = value => value.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(cell => cell.trim().replace(/\\\|/g, '|'));
      const headers = splitCells(line);
      const alignments = splitCells(lines[index + 1]).map(cell => cell.startsWith(':') && cell.endsWith(':') ? 'center' : cell.endsWith(':') ? 'right' : 'left');
      const wrap = document.createElement('div');
      wrap.className = 'chat-table-wrap';
      const table = document.createElement('table');
      const thead = document.createElement('thead');
      const headRow = document.createElement('tr');
      headers.forEach((value, cellIndex) => {
        const cell = document.createElement('th');
        cell.style.textAlign = alignments[cellIndex] ?? 'left';
        appendInlineMarkdown(cell, value);
        headRow.append(cell);
      });
      thead.append(headRow);
      table.append(thead);
      const tbody = document.createElement('tbody');
      index += 2;
      while (index < lines.length && lines[index].trim().startsWith('|')) {
        const values = splitCells(lines[index]);
        const row = document.createElement('tr');
        headers.forEach((_, cellIndex) => {
          const cell = document.createElement('td');
          cell.style.textAlign = alignments[cellIndex] ?? 'left';
          appendInlineMarkdown(cell, values[cellIndex] ?? '');
          row.append(cell);
        });
        tbody.append(row);
        index++;
      }
      table.append(tbody);
      wrap.append(table);
      target.append(wrap);
      continue;
    }

    if (/^\s{0,3}>/.test(line)) {
      const quoted = [];
      while (index < lines.length && /^\s{0,3}>/.test(lines[index])) quoted.push(lines[index++].replace(/^\s{0,3}>\s?/, ''));
      const quote = document.createElement('blockquote');
      renderMarkdown(quote, quoted.join('\n'));
      target.append(quote);
      continue;
    }

    const listItem = line.match(/^\s{0,3}([-+*]|\d+[.)])\s+(.+)$/);
    if (listItem) {
      const ordered = /^\d/.test(listItem[1]);
      const list = document.createElement(ordered ? 'ol' : 'ul');
      while (index < lines.length) {
        const item = lines[index].match(/^\s{0,3}([-+*]|\d+[.)])\s+(.+)$/);
        if (!item || /^\d/.test(item[1]) !== ordered) break;
        const li = document.createElement('li');
        appendInlineMarkdown(li, item[2]);
        list.append(li);
        index++;
      }
      target.append(list);
      continue;
    }

    const paragraphLines = [];
    while (index < lines.length && lines[index].trim() && (paragraphLines.length === 0 || !isBlockStart(lines[index]))) {
      paragraphLines.push(lines[index].trim());
      index++;
    }
    const paragraph = document.createElement('p');
    appendInlineMarkdown(paragraph, paragraphLines.join(' '));
    target.append(paragraph);
  }
}

function appendFeedbackControls(message) {
  const group = document.createElement('div');
  group.className = 'message-feedback';
  group.setAttribute('role', 'group');
  group.setAttribute('aria-label', 'Was this answer helpful?');

  const label = document.createElement('span');
  label.className = 'message-feedback__label';
  label.textContent = 'Helpful?';
  group.append(label);

  const status = document.createElement('span');
  status.className = 'message-feedback__status';
  status.setAttribute('role', 'status');
  status.setAttribute('aria-live', 'polite');

  const buttons = ['helpful', 'not_helpful'].map(rating => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'message-feedback__button';
    button.setAttribute('aria-label', rating === 'helpful' ? 'Helpful' : 'Not helpful');
    button.setAttribute('aria-pressed', 'false');
    button.title = rating === 'helpful' ? 'Helpful' : 'Not helpful';
    button.textContent = rating === 'helpful' ? '👍' : '👎';
    button.addEventListener('click', async () => {
      if (buttons.some(item => item.disabled)) return;
      buttons.forEach(item => { item.disabled = true; });
      status.textContent = 'Sending…';
      try {
        const response = await fetch(FEEDBACK_ENDPOINT, {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ rating }),
        });
        if (!response.ok) throw new Error(response.status === 429 ? 'limit' : 'unavailable');
        buttons.forEach(item => item.setAttribute('aria-pressed', String(item === button)));
        group.classList.add('message-feedback--submitted');
        status.textContent = 'Thanks for your feedback.';
        if (ownerToken) void loadFeedbackStats();
      } catch (error) {
        buttons.forEach(item => { item.disabled = false; });
        status.textContent = error instanceof Error && error.message === 'limit'
          ? 'Feedback limit reached for now.'
          : 'Couldn’t save that. Try again.';
      }
    });
    group.append(button);
    return button;
  });

  group.append(status);
  message.append(group);
}

async function loadVersions() {
  const select = $('[data-version]');
  const status = $('[data-version-status]');
  try {
    const response = await fetch(MANIFEST_URL, { cache: 'force-cache' });
    if (!response.ok) throw new Error(`Manifest returned ${response.status}`);
    const manifest = await response.json();
    if (!Array.isArray(manifest.versions) || manifest.versions.length === 0) throw new Error('Version list is empty');
    minecraftVersions = manifest.versions.filter(version => version.type === 'release');
    if (!minecraftVersions.length) throw new Error('Release list is empty');
    select.replaceChildren(...minecraftVersions.map(version => {
      const option = document.createElement('option');
      option.value = version.id;
      option.textContent = version.id;
      return option;
    }));
    const latest = minecraftVersions.find(version => version.id === manifest.latest?.release);
    select.value = latest?.id ?? minecraftVersions[0].id;
    select.disabled = false;
    status.textContent = `${minecraftVersions.length.toLocaleString()} released versions`;
  } catch (error) {
    console.warn('Could not load the official Minecraft version list:', error);
    select.replaceChildren(new Option('Version list unavailable', ''));
    status.textContent = 'Version list unavailable · Refresh to try again';
  }
}

function showFile(file) {
  const hint = $('[data-drop-hint]');
  const acceptable = SUPPORTED_TEXT_FILE.test(file.name);
  if (!acceptable) {
    hint.hidden = false;
    hint.textContent = 'Drop a Minecraft log, config, manifest, source or patch text file';
    setTimeout(() => { hint.hidden = true; }, 2400);
    return;
  }
  if (file.size > MAX_FILE_BYTES) {
    hint.hidden = false;
    hint.textContent = 'That file is over the 20 MB limit';
    setTimeout(() => { hint.hidden = true; }, 2400);
    return;
  }
  attachedFile = file;
  $('[data-file-name]').textContent = `${file.name} · ${formatBytes(file.size)}`;
  $('[data-file-row]').hidden = false;
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function hasFiles(event) {
  return Array.from(event.dataTransfer?.types ?? []).includes('Files');
}

function boot() {
  const composer = $('[data-composer]');
  const chat = $('.chat');
  const question = $('[data-question]');
  const send = $('[data-send]');
  const chatBody = $('[data-chat]');
  const welcomeMarkup = chatBody.querySelector('.welcome')?.cloneNode(true);
  const assistantStatus = $('[data-assistant-status]');
  const fileRow = $('[data-file-row]');
  const sidebar = $('[data-settings-toggle]').closest('.setup-sidebar');
  const settingsToggle = $('[data-settings-toggle]');
  const settingsPanel = $('[data-settings-panel]');
  const limitDialog = $('[data-limit-dialog]');
  let isSending = false;

  renderPlans($('[data-plan-options]'));
  renderPlans($('[data-upgrade-options]'));
  try { ownerToken = sessionStorage.getItem(OWNER_TOKEN_KEY) ?? ''; } catch { ownerToken = ''; }
  void loadVersions();
  void loadUsage();

  question.addEventListener('input', () => {
    send.disabled = isSending || !question.value.trim();
    question.style.height = 'auto';
    question.style.height = `${Math.min(question.scrollHeight, 190)}px`;
  });
  question.addEventListener('keydown', event => {
    if (event.key !== 'Enter' || event.shiftKey || event.isComposing || event.keyCode === 229) return;
    event.preventDefault();
    composer.requestSubmit();
  });

  $('[data-remove-file]').addEventListener('click', () => {
    attachedFile = null;
    fileRow.hidden = true;
  });

  let dragDepth = 0;
  const dropHint = $('[data-drop-hint]');
  const showDrop = event => {
    if (!hasFiles(event)) return;
    event.preventDefault();
    dragDepth++;
    chat.classList.add('is-dragging');
    composer.classList.add('is-dragging');
    dropHint.textContent = 'Release to attach file';
    dropHint.hidden = false;
  };
  const leaveDrop = event => {
    if (!hasFiles(event)) return;
    event.preventDefault();
    dragDepth = Math.max(0, dragDepth - 1);
    if (dragDepth === 0) {
      chat.classList.remove('is-dragging');
      composer.classList.remove('is-dragging');
      dropHint.hidden = true;
    }
  };
  chat.addEventListener('dragenter', showDrop);
  chat.addEventListener('dragover', event => { if (hasFiles(event)) event.preventDefault(); });
  chat.addEventListener('dragleave', leaveDrop);
  chat.addEventListener('drop', event => {
    if (!event.dataTransfer?.files?.length) return;
    event.preventDefault();
    dragDepth = 0;
    chat.classList.remove('is-dragging');
    composer.classList.remove('is-dragging');
    dropHint.hidden = true;
    showFile(event.dataTransfer.files[0]);
  });

  composer.addEventListener('submit', event => {
    event.preventDefault();
    if (isSending || !question.value.trim()) return;
    const prompt = question.value.trim();
    const attached = attachedFile;
    isSending = true;
    send.disabled = true;
    question.disabled = true;
    $('[data-clear-chat]').disabled = true;
    assistantStatus.textContent = 'Claude is thinking…';
    question.value = '';
    question.style.height = 'auto';
    const userMessage = appendMessage(chatBody, 'user', attached ? `${prompt}\n\nAttached: ${attached.name}` : prompt);
    const pendingMessage = appendMessage(chatBody, 'assistant', 'Thinking…', 'chat-message--pending');
    void (async () => {
      let attachment = null;
      if (attached) {
        try {
          const fullText = await attached.text();
          if (fullText.length > MAX_LOG_CHARS && !/\.log$/i.test(attached.name)) {
            throw new Error('That source or config file is over 60,000 characters. Attach a smaller file or just the relevant section.');
          }
          const text = fullText.length > MAX_LOG_CHARS
            ? `${fullText.slice(0, 45_000)}\n\n[Middle of log omitted to fit the 60,000 character limit]\n\n${fullText.slice(-15_000)}`
            : fullText;
          attachment = { name: attached.name, text };
        } catch (error) {
          userMessage.remove();
          pendingMessage.remove();
          question.value = prompt;
          question.style.height = 'auto';
          question.style.height = `${Math.min(question.scrollHeight, 190)}px`;
          assistantStatus.textContent = error instanceof Error ? error.message : 'Could not read that text file. Try saving it as UTF-8.';
          isSending = false;
          question.disabled = false;
          send.disabled = !question.value.trim();
          $('[data-clear-chat]').disabled = false;
          return;
        }
      }

      const messages = [...conversation.slice(-18), { role: 'user', content: prompt }];
      const version = $('[data-version]').value.trim();
      const loader = $('[data-loader]').value;
      const java = $('[data-java]').value;
      const includeSetup = $('[data-include-setup]').checked;

      try {
        const response = await fetch(CHAT_ENDPOINT, {
          method: 'POST',
          headers: { 'content-type': 'application/json', ...(ownerToken ? { authorization: `Bearer ${ownerToken}` } : {}) },
          body: JSON.stringify({ messages, minecraft: includeSetup ? { version, loader, java } : {}, attachment }),
        });
        const result = await response.json().catch(() => ({}));
        if (response.status === 402 && result.limitReached) {
          userMessage.remove();
          pendingMessage.remove();
          question.value = prompt;
          question.style.height = 'auto';
          question.style.height = `${Math.min(question.scrollHeight, 190)}px`;
          updateUsage(result.usage);
          limitDialog.showModal();
          assistantStatus.textContent = 'You have reached this month’s free AI allowance.';
          return;
        }
        if (!response.ok) throw new Error(result.message || 'Claude could not answer this message. Try again shortly.');
        if (typeof result.answer !== 'string' || !result.answer.trim()) throw new Error('Claude returned an empty answer. Try again.');

        pendingMessage.classList.remove('chat-message--pending');
        renderMarkdown(pendingMessage, result.answer);
        appendFeedbackControls(pendingMessage);
        chatBody.scrollTop = chatBody.scrollHeight;
        conversation.push({ role: 'user', content: prompt }, { role: 'assistant', content: result.answer });
        if (conversation.length > 18) conversation = conversation.slice(-18);
        updateUsage(result.usage);
        attachedFile = null;
        fileRow.hidden = true;
        send.disabled = true;
        assistantStatus.textContent = 'Claude replied. Your chat is only kept in this browser tab.';
        if (result.limitReached) {
          assistantStatus.textContent = 'Claude finished your request. This month’s free AI allowance is now used up.';
          limitDialog.showModal();
        }
      } catch (error) {
        userMessage.remove();
        pendingMessage.remove();
        question.value = prompt;
        question.style.height = 'auto';
        question.style.height = `${Math.min(question.scrollHeight, 190)}px`;
        const message = error instanceof Error ? error.message : 'Claude could not answer this message. Try again.';
        appendMessage(chatBody, 'assistant', message, 'chat-message--error');
        assistantStatus.textContent = 'Your message is still in the box so you can try again.';
      } finally {
        isSending = false;
        question.disabled = false;
        send.disabled = !question.value.trim();
        $('[data-clear-chat]').disabled = false;
      }
    })();
  });

  settingsToggle.addEventListener('click', () => {
    const open = settingsPanel.hidden;
    settingsPanel.hidden = !open;
    settingsToggle.setAttribute('aria-expanded', String(open));
  });
  $('[data-settings-close]').addEventListener('click', () => {
    settingsPanel.hidden = true;
    settingsToggle.setAttribute('aria-expanded', 'false');
  });
  $$('[data-limit-close]').forEach(button => button.addEventListener('click', () => limitDialog.close()));
  limitDialog.addEventListener('click', event => { if (event.target === limitDialog) limitDialog.close(); });
  $('[data-owner-form]').addEventListener('submit', async event => {
    event.preventDefault();
    const input = $('[data-owner-code]');
    const submit = $('[data-owner-submit]');
    const status = $('[data-owner-status]');
    const code = input.value;
    input.value = '';
    if (!code) {
      status.dataset.state = 'error';
      status.textContent = 'Enter a code.';
      return;
    }
    submit.disabled = true;
    status.dataset.state = '';
    status.textContent = 'Checking owner code…';
    try {
      const response = await fetch(OWNER_ENDPOINT, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ code }),
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok || typeof result.token !== 'string') throw new Error(response.status === 401 ? 'Code not recognized.' : (result.message || 'Code could not be applied.'));
      ownerToken = result.token;
      try { sessionStorage.setItem(OWNER_TOKEN_KEY, ownerToken); } catch { /* The token still works until this page is closed. */ }
      updateUsage({ unlimited: true });
      void loadFeedbackStats();
      status.dataset.state = 'success';
      status.textContent = 'Owner access is active in this browser tab.';
    } catch (error) {
      status.dataset.state = 'error';
      status.textContent = error instanceof Error ? error.message : 'Could not unlock owner access.';
    } finally {
      submit.disabled = false;
    }
  });
  $('[data-clear-chat]').addEventListener('click', () => {
    if (isSending) return;
    conversation = [];
    chatBody.classList.remove('has-messages');
    chatBody.replaceChildren(welcomeMarkup?.cloneNode(true) ?? document.createElement('div'));
    question.value = '';
    question.style.height = 'auto';
    attachedFile = null;
    fileRow.hidden = true;
    send.disabled = true;
    assistantStatus.textContent = 'This chat was cleared from this browser tab.';
  });
  $('[data-mobile-setup]').addEventListener('click', event => {
    const open = sidebar.classList.toggle('is-open');
    event.currentTarget.setAttribute('aria-expanded', String(open));
  });
}

boot();
