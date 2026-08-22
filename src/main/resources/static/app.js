/* =====================================================================
   Ham dung chung cho ca hai trang. Khong dung framework, khong buoc build.
   ===================================================================== */

const KEY_STORAGE = 'rag.apiKey';

let authState = null;

/* The app can be served under a reverse-proxy prefix (server.servlet.context-path),
   so absolute paths must be rebased. app.js always sits next to the pages, which
   makes its own src the reliable way to find that prefix. */
const BASE = (() => {
    const self = document.currentScript;
    const dir = self && self.src
        ? new URL('.', self.src).pathname
        : window.location.pathname.replace(/[^/]*$/, '');
    return dir.replace(/\/$/, '');
})();

function url(path) {
    if (typeof path !== 'string' || !path.startsWith('/')) return path;
    if (BASE && (path === BASE || path.startsWith(BASE + '/'))) return path;
    return BASE + path;
}

function apiKey() {
    return localStorage.getItem(KEY_STORAGE) || '';
}

function setApiKey(value) {
    if (value) localStorage.setItem(KEY_STORAGE, value.trim());
    else localStorage.removeItem(KEY_STORAGE);
}

function cookie(name) {
    const hit = document.cookie.split('; ').find(c => c.startsWith(name + '='));
    return hit ? decodeURIComponent(hit.substring(name.length + 1)) : '';
}

function authHeaders(extra) {
    const h = Object.assign({}, extra || {});
    const k = apiKey();
    if (k) h['X-API-Key'] = k;
    const csrf = cookie('XSRF-TOKEN');
    if (csrf) h['X-XSRF-TOKEN'] = csrf;
    return h;
}

// Cache ca LOI GOI dang chay, khong chi ket qua: boot() va mountAuthBadge() cung goi
// gan nhu dong thoi, neu chi cache ket qua thi /me bi goi hai lan moi lan tai trang.
let authPromise = null;

async function loadAuth() {
    if (authState) return authState;
    if (!authPromise) {
        authPromise = (async () => {
            try {
                const res = await fetch(url('/api/v1/rag/me'), { credentials: 'same-origin' });
                authState = res.ok ? await res.json() : { ssoEnabled: false, authenticated: false };
            } catch (e) {
                authState = { ssoEnabled: false, authenticated: false };
            }
            return authState;
        })();
    }
    return authPromise;
}

function goToLogin(loginUrl) {
    window.location.href = url(loginUrl || '/oauth2/authorization/entra');
}

async function api(path, options = {}) {
    const opts = Object.assign({}, options);
    // Required: without it the session cookie is not sent and a signed-in user
    // still gets 401 on every call.
    opts.credentials = 'same-origin';
    opts.headers = authHeaders(
        Object.assign(opts.body instanceof FormData ? {} : { 'Content-Type': 'application/json' },
            options.headers));

    let response;
    try {
        response = await fetch(url(path), opts);
    } catch (e) {
        toast('Không kết nối được tới server. Kiểm tra ứng dụng còn chạy không.', 'error');
        throw e;
    }

    if (response.status === 401) {
        let loginUrl = null;
        try {
            loginUrl = (await response.clone().json()).loginUrl || null;
        } catch (ignored) { /* body khong phai JSON */ }
        if (loginUrl || (authState && authState.ssoEnabled)) {
            toast('Phiên đăng nhập đã hết hạn. Đang chuyển tới trang đăng nhập…', 'warn');
            setTimeout(() => goToLogin(loginUrl), 800);
        } else {
            notifyMissingKey();
        }
        throw new Error('Chưa xác thực');
    }
    if (response.status === 403) {
        toast('Tài khoản của bạn không có quyền thực hiện thao tác này.', 'error');
        throw new Error('Khong du quyen');
    }
    if (response.status === 429) {
        toast('Bạn gửi quá nhiều yêu cầu. Chờ một phút rồi thử lại.', 'warn');
        throw new Error('rate limited');
    }
    if (!response.ok) {
        let message = 'Lỗi ' + response.status;
        try {
            const body = await response.json();
            if (body.error) message = body.error;
            if (body.traceId) message += ' (mã tra cứu: ' + body.traceId + ')';
        } catch (ignored) { /* body khong phai JSON */ }
        toast(message, 'error');
        throw new Error(message);
    }
    if (response.status === 204) return null;
    const text = await response.text();
    return text ? JSON.parse(text) : null;
}

function toast(message, kind) {
    let host = document.getElementById('toasts');
    if (!host) {
        host = document.createElement('div');
        host.id = 'toasts';
        document.body.appendChild(host);
    }
    const el = document.createElement('div');
    el.className = 'toast' + (kind ? ' ' + kind : '');
    el.textContent = message;
    host.appendChild(el);
    setTimeout(() => el.remove(), kind === 'error' ? 9000 : 5000);
}

let lastKeyNotice = 0;

// Never opens the key dialog by itself. Doing that on 401 created a loop: saving an
// empty box cleared the key, the page reloaded, 401 again, dialog again. Repeated
// 401s within a few seconds collapse into one toast.
function notifyMissingKey() {
    const now = Date.now();
    if (now - lastKeyNotice < 3000) return;
    lastKeyNotice = now;
    toast('Chưa có API key nên không tải được dữ liệu. Bấm nút "API key" ở góc trên phải để đặt.',
        'warn');
}

function promptForKey() {
    if (document.getElementById('key-modal')) return;
    const backdrop = document.createElement('div');
    backdrop.className = 'modal-backdrop';
    backdrop.id = 'key-modal';
    const current = apiKey();
    backdrop.innerHTML = `
      <div class="modal" style="max-width:520px">
        <header>API key</header>
        <div class="body">
          <p class="muted" style="margin-top:0">
            ${current
              ? 'Trình duyệt này đang lưu một API key. Dán key khác để thay, hoặc xoá trống rồi lưu để bỏ key.'
              : 'Dán API key được cấp vào đây. Key chỉ được lưu trong trình duyệt của bạn, không gửi đi đâu khác ngoài server này.'}
          </p>
          <label for="key-input">X-API-Key</label>
          <input id="key-input" type="password" autocomplete="off" placeholder="dán key vào đây">
          <p class="faint" style="font-size:.82rem">
            Trên máy dev có thể chạy với <span class="mono">RAG_ALLOW_ANONYMOUS=true</span>
            để không cần key.
          </p>
        </div>
        <footer>
          <button class="ghost" id="key-cancel">Đóng</button>
          <button class="primary" id="key-save">Lưu và tải lại</button>
        </footer>
      </div>`;
    document.body.appendChild(backdrop);
    const input = backdrop.querySelector('#key-input');
    input.value = current;
    input.focus();

    // One close path for every way out, so the Esc handler is always unbound. Removing it
    // only inside the Esc branch leaked one live listener per open.
    const close = () => {
        document.removeEventListener('keydown', onEsc);
        backdrop.remove();
    };
    function onEsc(e) { if (e.key === 'Escape') close(); }
    document.addEventListener('keydown', onEsc);

    backdrop.onclick = (e) => { if (e.target === backdrop) close(); };
    backdrop.querySelector('#key-cancel').onclick = close;
    backdrop.querySelector('#key-save').onclick = () => {
        const value = input.value.trim();
        setApiKey(value);
        close();
        if (value) {
            location.reload();
        } else {
            toast('Đã xoá API key khỏi trình duyệt.', 'warn');
        }
    };
    input.onkeydown = (e) => { if (e.key === 'Enter') backdrop.querySelector('#key-save').click(); };
}

function fmtInt(n) {
    if (n === null || n === undefined) return '—';
    return Number(n).toLocaleString('vi-VN');
}

function fmtMoney(usd) {
    if (usd === null || usd === undefined) return '—';
    const v = Number(usd);
    if (v === 0) return '$0';
    if (v < 0.01) return '$' + v.toFixed(5);
    return '$' + v.toFixed(4);
}

function fmtMs(ms) {
    if (ms === null || ms === undefined) return '—';
    const v = Number(ms);
    // Percentiles come back as doubles; printing 1234.5678 ms reads like a defect.
    return v < 1000 ? Math.round(v) + ' ms' : (v / 1000).toFixed(1) + ' s';
}

function fmtPercent(v) {
    if (v === null || v === undefined) return '—';
    return Number(v).toFixed(1) + '%';
}

function fmtScore(v) {
    if (v === null || v === undefined) return '—';
    return Number(v).toFixed(3);
}

function fmtDate(value) {
    if (!value) return '—';
    const d = new Date(value);
    if (isNaN(d.getTime())) return String(value);
    return d.toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' });
}

function fmtRelative(value) {
    if (!value) return '';
    const d = new Date(value);
    if (isNaN(d.getTime())) return '';
    const secs = Math.round((Date.now() - d.getTime()) / 1000);
    if (secs < 60) return 'vừa xong';
    if (secs < 3600) return Math.floor(secs / 60) + ' phút trước';
    if (secs < 86400) return Math.floor(secs / 3600) + ' giờ trước';
    if (secs < 172800) return 'hôm qua';
    if (secs < 604800) return Math.floor(secs / 86400) + ' ngày trước';
    return d.toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit', year: '2-digit' });
}

function fmtSeconds(ms) {
    return (Math.max(0, ms) / 1000).toFixed(1).replace('.', ',') + ' s';
}

/** Which bucket a timestamp falls in, for grouping a history list by recency. */
function dayBucket(value) {
    const d = new Date(value);
    if (isNaN(d.getTime())) return { key: 'old', label: 'Cũ hơn' };
    const midnight = new Date(); midnight.setHours(0, 0, 0, 0);
    const days = Math.floor((midnight.getTime() - d.getTime()) / 86400000);
    if (days < 0) return { key: 'today', label: 'Hôm nay' };
    if (days === 0) return { key: 'yesterday', label: 'Hôm qua' };
    if (days < 7) return { key: 'week', label: '7 ngày qua' };
    if (days < 30) return { key: 'month', label: '30 ngày qua' };
    return { key: 'old', label: 'Cũ hơn' };
}

function escapeHtml(text) {
    if (text === null || text === undefined) return '';
    return String(text)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// Minimal Markdown renderer, deliberately not a library: CSP and internal networks
// block CDN scripts. Everything is escaped before any tag is added, so no XSS path.
function renderMarkdown(src) {
    if (!src) return '';
    const lines = escapeHtml(src).split('\n');
    const out = [];
    // Stack of open lists, one entry per indent level. Sub-bullets used to be flattened
    // onto the top level, which turned a structured answer into a flat wall of dashes.
    let lists = [];
    let inTable = false;
    let tableRow = 0;
    let inFence = false;

    const closeLists = (deeperThan) => {
        while (lists.length && lists[lists.length - 1].indent > deeperThan) {
            out.push('</' + lists.pop().tag + '>');
        }
    };
    const closeAllLists = () => closeLists(-1);
    const closeTable = () => {
        if (inTable) { out.push('</tbody></table></div>'); inTable = false; tableRow = 0; }
    };
    const closeBlocks = () => { closeAllLists(); closeTable(); };
    const indentOf = (text) => text.match(/^[ \t]*/)[0].replace(/\t/g, '    ').length;

    for (let i = 0; i < lines.length; i++) {
        let line = lines[i];
        const trimmed = line.trim();

        // Fenced code stays verbatim. Content was escaped up front, so no tag can slip in.
        if (/^(```|~~~)/.test(trimmed)) {
            if (inFence) { out.push('</code></pre>'); inFence = false; }
            else { closeBlocks(); out.push('<pre class="code"><code>'); inFence = true; }
            continue;
        }
        if (inFence) { out.push(line); continue; }

        if (!trimmed) { closeBlocks(); continue; }

        if (trimmed.startsWith('|')) {
            const cells = trimmed.replace(/^\||\|$/g, '').split('|').map(c => c.trim());
            const isSeparator = cells.every(c => /^:?-{2,}:?$/.test(c));
            if (isSeparator) continue;
            if (!inTable) {
                closeBlocks();
                out.push('<div class="scroll"><table><tbody>');
                inTable = true;
                tableRow = 0;
            }
            // First row of a pipe table is its header in the documents we ingest.
            const tag = tableRow === 0 ? 'th' : 'td';
            out.push('<tr>' + cells.map(c => '<' + tag + '>' + inline(c) + '</' + tag + '>').join('') + '</tr>');
            tableRow++;
            continue;
        } else if (inTable) {
            closeBlocks();
        }

        const heading = trimmed.match(/^(#{1,6})\s+(.*)$/);
        if (heading) {
            closeBlocks();
            const level = Math.min(6, heading[1].length + 2);
            out.push('<h' + level + '>' + inline(heading[2]) + '</h' + level + '>');
            continue;
        }

        const bullet = trimmed.match(/^(?:[-*+]|(\d+)[.)])\s+/);
        if (bullet) {
            const wanted = bullet[1] === undefined ? 'ul' : 'ol';
            const indent = indentOf(line);
            closeTable();
            closeLists(indent);
            const top = lists[lists.length - 1];
            if (!top || indent > top.indent) {
                out.push(openList(wanted, bullet[1]));
                lists.push({ tag: wanted, indent: indent });
            } else if (top.tag !== wanted) {
                out.push('</' + lists.pop().tag + '>');
                out.push(openList(wanted, bullet[1]));
                lists.push({ tag: wanted, indent: indent });
            }
            out.push('<li>' + inline(trimmed.slice(bullet[0].length)) + '</li>');
            continue;
        }

        if (trimmed.startsWith('&gt;')) {
            closeBlocks();
            out.push('<blockquote class="muted">' + inline(trimmed.slice(4).trim()) + '</blockquote>');
            continue;
        }

        closeAllLists();
        out.push('<p>' + inline(trimmed) + '</p>');
    }
    if (inFence) out.push('</code></pre>');
    closeBlocks();
    return out.join('\n');

    /* Answers cite "3." for khoan 3; without start= the browser renumbers it to 1 and the
       citation quietly points at the wrong clause. */
    function openList(tag, firstNumber) {
        if (tag !== 'ol') return '<ul>';
        const n = Number(firstNumber);
        return n > 1 ? '<ol start="' + n + '">' : '<ol>';
    }

    function inline(text) {
        return text
            .replace(/`([^`]+)`/g, '<code>$1</code>')
            .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
            .replace(/(^|\s)\*([^*]+)\*/g, '$1<em>$2</em>')
            .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g,
                '<a href="$2" target="_blank" rel="noopener">$1</a>');
    }
}

/* ------------------------------------------------------ Phien dang nhap */

/** Chu cai dau de ve avatar: "Bui Duc Quang" -> BQ, "quangbd@bsc" -> QU. */
function initialsOf(name) {
    const raw = (name || '').trim();
    if (!raw) return '?';
    const local = raw.includes('@') ? raw.split('@')[0] : raw;
    const words = local.split(/[\s._-]+/).filter(Boolean);
    if (words.length >= 2) {
        return (words[0][0] + words[words.length - 1][0]).toUpperCase();
    }
    return local.substring(0, 2).toUpperCase();
}

/** Ten ngan de hien: bo phan @domain cho khoi tran thanh tren. */
function shortNameOf(name) {
    const raw = (name || '').trim();
    return raw.includes('@') ? raw.split('@')[0] : raw;
}

/** True khi la nguoi dung thuong da dang nhap. Che do an danh KHONG bi han che. */
function isRestrictedUser(auth) {
    return auth && auth.authenticated === true && auth.admin === false;
}

/**
 * Gan thong tin nguoi dung vao thanh tren: mot vien thuoc co avatar, bam ra menu.
 *
 * Khi CHUA bat SSO thi khong dung gi ca - giao dien giu nguyen nut "API key" nhu cu,
 * de moi truong dev khong bi anh huong.
 */
async function mountAuthBadge() {
    // Man hoi dap dat cum nay o goc tren ben phai cua vung chinh (.main-top), cac trang
    // cong cu van dung thanh ngang (.topbar). Ca hai deu danh dau [data-topbar].
    const bar = document.querySelector('[data-topbar]') || document.querySelector('.topbar');
    if (!bar) return;
    const auth = await loadAuth();

    // Nguoi khong phai quan tri bam vao "Quan tri" chi nhan 403 -> bo han khoi DOM.
    // Tim tren CA trang: man hoi dap dat dieu huong o cot ben, cac trang cong cu dat o
    // thanh ngang - neu chi tim trong `bar` thi link o cot ben khong bi xoa, chi bi CSS
    // an di va van bam duoc bang Tab.
    if (isRestrictedUser(auth)) {
        document.querySelectorAll('a[href="admin.html"]').forEach(a => a.remove());
    }

    if (!auth.ssoEnabled) return;

    // Da dang nhap bang tai khoan cong ty thi khong con can API key trong trinh duyet.
    const keyBtn = bar.querySelector('button[onclick="promptForKey()"]');
    if (keyBtn && auth.authenticated) keyBtn.remove();

    const anchor = document.getElementById('theme-toggle');

    if (!auth.authenticated) {
        const btn = document.createElement('button');
        btn.className = 'tiny';
        btn.textContent = 'Đăng nhập';
        btn.onclick = () => goToLogin(auth.loginUrl);
        if (anchor) bar.insertBefore(btn, anchor);
        else bar.appendChild(btn);
        return;
    }

    const box = document.createElement('div');
    box.className = 'usermenu';

    const trigger = document.createElement('button');
    trigger.className = 'u-trigger';
    trigger.setAttribute('aria-haspopup', 'true');
    trigger.setAttribute('aria-expanded', 'false');
    trigger.innerHTML = '<span class="u-avatar"></span><span class="u-name"></span>'
        + '<span class="u-caret">▼</span>';
    trigger.querySelector('.u-avatar').textContent = initialsOf(auth.displayName);
    trigger.querySelector('.u-name').textContent = shortNameOf(auth.displayName);
    trigger.title = auth.displayName || '';

    const pop = document.createElement('div');
    pop.className = 'u-pop';
    pop.hidden = true;

    const scopeText = auth.allDepartments
        ? 'tất cả'
        : ((auth.departments || []).join(', ') || 'chưa được cấp');
    pop.innerHTML =
        '<div class="u-id"><span class="u-avatar"></span>'
        + '<div><b></b><small></small></div></div>'
        + '<div class="u-sep"></div>'
        + '<div class="u-row"><span>Quyền</span><span class="r-role"></span></div>'
        + '<div class="u-row"><span>Nhóm tài liệu đọc được</span><span class="r-dep"></span></div>'
        + '<div class="u-sep"></div>';
    pop.querySelector('.u-avatar').textContent = initialsOf(auth.displayName);
    pop.querySelector('.u-id b').textContent = auth.displayName || '';
    pop.querySelector('.u-id small').textContent =
        auth.admin ? 'Quản trị hệ thống' : 'Người dùng';
    pop.querySelector('.r-role').textContent = (auth.roles || []).join(', ') || '—';
    pop.querySelector('.r-dep').textContent = scopeText;

    const out = document.createElement('button');
    out.className = 'danger u-out';
    out.textContent = 'Đăng xuất';
    // Spring Security yeu cau POST cho /logout khi CSRF dang bat; gui bang form
    // an de trinh duyet tu dieu huong theo phan hoi.
    out.onclick = () => {
        const form = document.createElement('form');
        form.method = 'POST';
        form.action = url(auth.logoutUrl || '/logout');
        const token = cookie('XSRF-TOKEN');
        if (token) {
            const input = document.createElement('input');
            input.type = 'hidden';
            input.name = '_csrf';
            input.value = token;
            form.appendChild(input);
        }
        document.body.appendChild(form);
        form.submit();
    };
    pop.appendChild(out);

    const close = () => {
        pop.hidden = true;
        trigger.setAttribute('aria-expanded', 'false');
    };
    trigger.onclick = (e) => {
        e.stopPropagation();
        pop.hidden = !pop.hidden;
        trigger.setAttribute('aria-expanded', String(!pop.hidden));
    };
    document.addEventListener('click', (e) => { if (!box.contains(e.target)) close(); });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });

    box.appendChild(trigger);
    box.appendChild(pop);
    if (anchor) bar.insertBefore(box, anchor);
    else bar.appendChild(box);
}

/* Thanh tren tu xuong dong khi hep, nen chieu cao KHONG phai hang so. Ghi lai chieu
   cao thuc vao --topbar-h de ngan keo (drawer) tren mobile khong nam de len thanh do. */
function trackTopbarHeight() {
    const bar = document.querySelector('[data-topbar]') || document.querySelector('.topbar');
    if (!bar) return;
    const apply = () => document.documentElement.style.setProperty(
        '--topbar-h', bar.offsetHeight + 'px');
    apply();
    if (window.ResizeObserver) new ResizeObserver(apply).observe(bar);
    else window.addEventListener('resize', apply);
}

document.addEventListener('DOMContentLoaded', () => {
    trackTopbarHeight();
    mountAuthBadge()
        .then(trackTopbarHeight)   // badge them phan tu vao thanh tren
        .catch(() => { /* thieu badge khong duoc lam hong ca trang */ });
});

/* ------------------------------------------------------------- Dropdown */

/*
 * The option list of a native <select> is drawn by the OS, not by the page: it ignores
 * every border-radius we set and it ignores the theme. In dark mode that combination is
 * actively broken - the options inherit the select's near-white `color` but the popup
 * keeps the OS's white background, so the list renders white-on-white.
 *
 * So we draw the list ourselves. The real <select> stays in the DOM as the single source
 * of truth (just display:none), which is what lets every existing `.value`, `.innerHTML`
 * and onchange handler in the three pages keep working without being touched.
 */

const DD_CARET = '<svg class="dd-caret" viewBox="0 0 24 24" width="14" height="14"'
    + ' fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"'
    + ' stroke-linejoin="round" aria-hidden="true"><path d="M6 9.5l6 6 6-6"/></svg>';

const DD_TICK = '<svg class="dd-tick" viewBox="0 0 24 24" width="13" height="13"'
    + ' fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round"'
    + ' stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7"/></svg>';

/** Multi-selects stay native: they are a visible list box, not a popup, so nothing is broken. */
function enhanceSelects(root) {
    (root || document).querySelectorAll('select:not([multiple]):not([data-native])')
        .forEach(enhanceSelect);
}

function enhanceSelect(sel) {
    if (sel.__dd) return;

    const wrap = document.createElement('div');
    wrap.className = 'dd';
    // Width/flex are usually set on the <select> itself; carry them to the wrapper or the
    // control loses its size the moment the select goes display:none.
    ['width', 'maxWidth', 'minWidth', 'flex'].forEach(k => {
        if (sel.style[k]) wrap.style[k] = sel.style[k];
    });
    sel.parentNode.insertBefore(wrap, sel);
    wrap.appendChild(sel);

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'dd-btn';
    btn.setAttribute('aria-haspopup', 'listbox');
    btn.setAttribute('aria-expanded', 'false');
    btn.innerHTML = '<span class="dd-label"></span>' + DD_CARET;
    if (sel.title) btn.title = sel.title;
    // A <label for="..."> points at the hidden select, so move the association.
    const labelled = sel.id && document.querySelector('label[for="' + sel.id + '"]');
    if (labelled) btn.setAttribute('aria-label', labelled.textContent.trim());

    const pop = document.createElement('div');
    pop.className = 'dd-pop';
    pop.setAttribute('role', 'listbox');
    pop.hidden = true;

    wrap.appendChild(btn);
    wrap.appendChild(pop);
    sel.__dd = { wrap: wrap, btn: btn, pop: pop, active: -1 };

    btn.addEventListener('click', (e) => { e.stopPropagation(); ddToggle(sel); });
    btn.addEventListener('keydown', (e) => ddKey(sel, e));
    pop.addEventListener('click', (e) => {
        // Clicking the scrollbar or a disabled row must not count as "clicked outside".
        e.stopPropagation();
        const item = e.target.closest('.dd-opt');
        if (!item || item.getAttribute('aria-disabled') === 'true') return;
        ddPick(sel, Number(item.dataset.i));
    });
    sel.addEventListener('change', () => ddSync(sel));

    // Option lists are rebuilt with innerHTML all over the admin page; without this the
    // popup would keep showing whatever was there at page load.
    new MutationObserver(() => ddSync(sel)).observe(sel, { childList: true, subtree: true });

    ddSync(sel);
}

/** Redraw button label and option list from the <select>. */
function ddSync(sel) {
    const dd = sel.__dd;
    if (!dd) return;
    dd.btn.disabled = sel.disabled;

    const chosen = sel.options[sel.selectedIndex];
    dd.btn.querySelector('.dd-label').textContent =
        chosen ? chosen.textContent.trim() : '—';

    let html = '';
    Array.prototype.forEach.call(sel.children, (child) => {
        if (child.tagName === 'OPTGROUP') {
            html += '<div class="dd-group">' + escapeHtml(child.label) + '</div>';
            Array.prototype.forEach.call(child.children, (o) => { html += ddItem(sel, o); });
        } else if (child.tagName === 'OPTION') {
            html += ddItem(sel, child);
        }
    });
    dd.pop.innerHTML = html || '<div class="dd-empty">Không có lựa chọn nào</div>';
}

function ddItem(sel, option) {
    const selected = option.index === sel.selectedIndex;
    return '<div class="dd-opt" role="option" data-i="' + option.index + '"'
        + ' aria-selected="' + selected + '"'
        + (option.disabled ? ' aria-disabled="true"' : '')
        + '>' + DD_TICK + '<span>' + escapeHtml(option.textContent.trim()) + '</span></div>';
}

function ddToggle(sel) {
    if (sel.__dd.pop.hidden) ddOpen(sel); else ddClose(sel);
}

function ddOpen(sel) {
    document.querySelectorAll('.dd.open').forEach(w => {
        if (w !== sel.__dd.wrap) ddClose(w.querySelector('select'));
    });
    const dd = sel.__dd;
    ddSync(sel);
    dd.pop.hidden = false;
    dd.wrap.classList.add('open');
    dd.btn.setAttribute('aria-expanded', 'true');

    // The composer sits at the bottom of the viewport, so a list that only ever opens
    // downwards would be cut off exactly where it is used most.
    const box = dd.btn.getBoundingClientRect();
    const below = window.innerHeight - box.bottom;
    dd.wrap.classList.toggle('up', below < dd.pop.offsetHeight + 16 && box.top > below);

    dd.active = sel.selectedIndex;
    ddPaintActive(sel);
    const hit = dd.pop.querySelector('[aria-selected="true"]');
    if (hit) hit.scrollIntoView({ block: 'nearest' });
}

function ddClose(sel) {
    const dd = sel && sel.__dd;
    if (!dd) return;
    dd.pop.hidden = true;
    dd.wrap.classList.remove('open', 'up');
    dd.btn.setAttribute('aria-expanded', 'false');
}

function ddPick(sel, index) {
    sel.selectedIndex = index;
    ddClose(sel);
    sel.__dd.btn.focus();
    ddSync(sel);
    // Bubbles, so inline onchange="" attributes and addEventListener both fire, exactly
    // as they would after a real click on a native option.
    sel.dispatchEvent(new Event('change', { bubbles: true }));
}

function ddPaintActive(sel) {
    const dd = sel.__dd;
    dd.pop.querySelectorAll('.dd-opt').forEach(el => {
        el.classList.toggle('active', Number(el.dataset.i) === dd.active);
    });
}

/** Step to the next option that can actually be chosen, skipping disabled ones. */
function ddStep(sel, delta) {
    const dd = sel.__dd;
    const total = sel.options.length;
    let i = dd.active;
    for (let n = 0; n < total; n++) {
        i = (i + delta + total) % total;
        if (!sel.options[i].disabled) break;
    }
    dd.active = i;
    ddPaintActive(sel);
    const hit = dd.pop.querySelector('.dd-opt.active');
    if (hit) hit.scrollIntoView({ block: 'nearest' });
}

function ddKey(sel, e) {
    const open = !sel.__dd.pop.hidden;
    if (!open) {
        if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            ddOpen(sel);
        }
        return;
    }
    if (e.key === 'Escape') { e.preventDefault(); ddClose(sel); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); ddStep(sel, 1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); ddStep(sel, -1); }
    else if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        if (sel.__dd.active >= 0) ddPick(sel, sel.__dd.active);
    } else if (e.key === 'Tab') {
        ddClose(sel);
    }
}

document.addEventListener('click', () => {
    document.querySelectorAll('.dd.open').forEach(w => ddClose(w.querySelector('select')));
});

/*
 * Assigning `.value` in script fires no event and mutates no attribute, so neither the
 * change listener nor the MutationObserver would notice. Wrapping the property is the
 * only hook that catches it - and it is what keeps call sites like
 * `modelSel.value = settings.model` working without editing any of them.
 */
(function trackProgrammaticSelectValue() {
    const proto = HTMLSelectElement.prototype;
    ['value', 'selectedIndex'].forEach((prop) => {
        const base = Object.getOwnPropertyDescriptor(proto, prop);
        if (!base || !base.set) return;
        Object.defineProperty(proto, prop, {
            configurable: true,
            enumerable: base.enumerable,
            get: function () { return base.get.call(this); },
            set: function (v) {
                base.set.call(this, v);
                if (this.__dd) ddSync(this);
            }
        });
    });
})();

document.addEventListener('DOMContentLoaded', () => enhanceSelects());

/* ---------------------------------------------------------------- Theme */

function initTheme() {
    const stored = localStorage.getItem('rag.theme');
    if (stored) document.documentElement.setAttribute('data-theme', stored);
    const btn = document.getElementById('theme-toggle');
    if (!btn) return;
    // Icon thay cho chu: goc thanh tren chi con hai bieu tuong, do roi hon nhieu.
    const SUN = '<svg viewBox="0 0 24 24" width="17" height="17" fill="none"'
        + ' stroke="currentColor" stroke-width="1.9" stroke-linecap="round" aria-hidden="true">'
        + '<circle cx="12" cy="12" r="4.2"/><path d="M12 2.4v2.1M12 19.5v2.1M2.4 12h2.1'
        + 'M19.5 12h2.1M5.2 5.2l1.5 1.5M17.3 17.3l1.5 1.5M18.8 5.2l-1.5 1.5'
        + 'M6.7 17.3l-1.5 1.5"/></svg>';
    const MOON = '<svg viewBox="0 0 24 24" width="17" height="17" fill="none"'
        + ' stroke="currentColor" stroke-width="1.9" stroke-linecap="round"'
        + ' stroke-linejoin="round" aria-hidden="true">'
        + '<path d="M20.5 14.6A8.6 8.6 0 1 1 9.4 3.5a6.9 6.9 0 0 0 11.1 11.1z"/></svg>';
    const sync = () => {
        const explicit = document.documentElement.getAttribute('data-theme');
        const dark = explicit
            ? explicit === 'dark'
            : window.matchMedia('(prefers-color-scheme: dark)').matches;
        btn.innerHTML = dark ? SUN : MOON;
        btn.title = dark ? 'Chuyển sang giao diện sáng' : 'Chuyển sang giao diện tối';
        btn.setAttribute('aria-label', btn.title);
    };
    btn.onclick = () => {
        const explicit = document.documentElement.getAttribute('data-theme');
        const dark = explicit
            ? explicit === 'dark'
            : window.matchMedia('(prefers-color-scheme: dark)').matches;
        const next = dark ? 'light' : 'dark';
        document.documentElement.setAttribute('data-theme', next);
        localStorage.setItem('rag.theme', next);
        sync();
    };
    sync();
}

/* ---------------------------------------------------------------- Modal */

function showModal(title, bodyHtml, footerHtml) {
    const backdrop = document.createElement('div');
    backdrop.className = 'modal-backdrop';
    backdrop.innerHTML = `
      <div class="modal">
        <header><span style="flex:1">${escapeHtml(title)}</span>
          <button class="ghost tiny" data-close>Đóng</button></header>
        <div class="body">${bodyHtml}</div>
        ${footerHtml ? '<footer>' + footerHtml + '</footer>' : ''}
      </div>`;
    document.body.appendChild(backdrop);
    backdrop.addEventListener('click', (e) => {
        if (e.target === backdrop || e.target.hasAttribute('data-close')) backdrop.remove();
    });
    return backdrop;
}

/**
 * Hop thoai xac nhan, thay cho window.confirm() cua trinh duyet.
 * Tra ve Promise<boolean>; huy bang Esc hoac bam ra ngoai deu coi la "khong".
 */
function confirmDialog(options) {
    const o = options || {};
    return new Promise((resolve) => {
        const backdrop = document.createElement('div');
        backdrop.className = 'modal-backdrop';
        backdrop.innerHTML =
            '<div class="modal" style="max-width:26rem">'
            + '<header>' + escapeHtml(o.title || 'Xác nhận') + '</header>'
            + '<div class="body"><p style="margin:0">' + escapeHtml(o.message || '') + '</p></div>'
            + '<footer><button class="ghost" data-no>' + escapeHtml(o.cancelLabel || 'Huỷ') + '</button>'
            + '<button class="' + (o.danger === false ? 'primary' : 'danger solid') + '" data-yes>'
            + escapeHtml(o.confirmLabel || 'Xoá') + '</button></footer></div>';
        document.body.appendChild(backdrop);

        let settled = false;
        const close = (answer) => {
            if (settled) return;
            settled = true;
            document.removeEventListener('keydown', onKey);
            backdrop.remove();
            resolve(answer);
        };
        function onKey(e) {
            if (e.key === 'Escape') close(false);
            if (e.key === 'Enter') close(true);
        }
        document.addEventListener('keydown', onKey);
        backdrop.addEventListener('click', (e) => {
            if (e.target === backdrop || e.target.hasAttribute('data-no')) close(false);
            else if (e.target.hasAttribute('data-yes')) close(true);
        });
        backdrop.querySelector('[data-yes]').focus();
    });
}

/**
 * Hop thoai nhap mot dong chu, thay cho window.prompt() cua trinh duyet: Chrome chan han
 * prompt() sau vai lan mo lien tiep, va no khong theo duoc giao dien sang/toi.
 * Tra ve Promise<string|null>; huy bang Esc / bam ra ngoai deu tra null.
 */
function promptDialog(options) {
    const o = options || {};
    return new Promise((resolve) => {
        const backdrop = document.createElement('div');
        backdrop.className = 'modal-backdrop';
        backdrop.innerHTML =
            '<div class="modal" style="max-width:30rem">'
            + '<header>' + escapeHtml(o.title || 'Nh\u1eadp n\u1ed9i dung') + '</header>'
            + '<div class="body">'
            + (o.message ? '<p class="muted" style="margin-top:0">' + escapeHtml(o.message) + '</p>' : '')
            + '<textarea id="prompt-input" rows="3"></textarea></div>'
            + '<footer><button class="ghost" data-no>' + escapeHtml(o.cancelLabel || 'B\u1ecf qua')
            + '</button><button class="primary" data-yes>' + escapeHtml(o.confirmLabel || 'G\u1eedi')
            + '</button></footer></div>';
        document.body.appendChild(backdrop);

        const input = backdrop.querySelector('#prompt-input');
        input.placeholder = o.placeholder || '';
        input.value = o.value || '';
        input.focus();

        let settled = false;
        const close = (answer) => {
            if (settled) return;
            settled = true;
            document.removeEventListener('keydown', onKey);
            backdrop.remove();
            resolve(answer);
        };
        function onKey(e) {
            if (e.key === 'Escape') close(null);
            // Ctrl+Enter gui; Enter tran de xuong dong trong o nhieu dong.
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) close(input.value.trim());
        }
        document.addEventListener('keydown', onKey);
        backdrop.addEventListener('click', (e) => {
            if (e.target === backdrop || e.target.hasAttribute('data-no')) close(null);
            else if (e.target.hasAttribute('data-yes')) close(input.value.trim());
        });
    });
}

/* navigator.clipboard is unavailable on plain HTTP, which is how the UAT box is
   reached, so fall back to a hidden textarea + execCommand there. */
async function copyText(text) {
    try {
        if (navigator.clipboard && window.isSecureContext) {
            await navigator.clipboard.writeText(text);
        } else {
            const ta = document.createElement('textarea');
            ta.value = text;
            ta.style.cssText = 'position:fixed;opacity:0;pointer-events:none';
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            ta.remove();
        }
        return true;
    } catch (e) {
        toast('Trình duyệt không cho phép copy. Hãy bôi đen rồi Ctrl+C.', 'warn');
        return false;
    }
}

document.addEventListener('DOMContentLoaded', initTheme);
