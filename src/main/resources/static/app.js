/* =====================================================================
   Ham dung chung cho ca hai trang. Khong dung framework, khong buoc build.
   ===================================================================== */

const KEY_STORAGE = 'rag.apiKey';

/** Lay API key da luu trong trinh duyet. */
function apiKey() {
    return localStorage.getItem(KEY_STORAGE) || '';
}

function setApiKey(value) {
    if (value) localStorage.setItem(KEY_STORAGE, value.trim());
    else localStorage.removeItem(KEY_STORAGE);
}

function authHeaders(extra) {
    const h = Object.assign({}, extra || {});
    const k = apiKey();
    if (k) h['X-API-Key'] = k;
    return h;
}

/**
 * Goi API. Tu dong gan API key, tu dong hien hop nhap key khi bi 401,
 * va bao loi bang thong bao tieng Viet thay vi de trang trang.
 */
async function api(path, options = {}) {
    const opts = Object.assign({}, options);
    opts.headers = authHeaders(
        Object.assign(opts.body instanceof FormData ? {} : { 'Content-Type': 'application/json' },
            options.headers));

    let response;
    try {
        response = await fetch(path, opts);
    } catch (e) {
        toast('Không kết nối được tới server. Kiểm tra ứng dụng còn chạy không.', 'error');
        throw e;
    }

    if (response.status === 401) {
        promptForKey();
        throw new Error('Thiếu hoặc sai API key');
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

/* ------------------------------------------------------------------ Toast */

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

/* ------------------------------------------------------------ Hop nhap key */

function promptForKey() {
    if (document.getElementById('key-modal')) return;
    const backdrop = document.createElement('div');
    backdrop.className = 'modal-backdrop';
    backdrop.id = 'key-modal';
    backdrop.innerHTML = `
      <div class="modal" style="max-width:520px">
        <header>Cần API key</header>
        <div class="body">
          <p class="muted" style="margin-top:0">
            Hệ thống yêu cầu API key để xác thực. Dán key được cấp vào đây — key được lưu
            trong trình duyệt của bạn, không gửi đi đâu khác ngoài server này.
          </p>
          <label for="key-input">X-API-Key</label>
          <input id="key-input" type="password" autocomplete="off" placeholder="dán key vào đây">
          <p class="faint" style="font-size:.82rem">
            Trên máy dev có thể chạy với <span class="mono">RAG_ALLOW_ANONYMOUS=true</span>
            để không cần key.
          </p>
        </div>
        <footer>
          <button class="ghost" id="key-cancel">Bỏ qua</button>
          <button class="primary" id="key-save">Lưu và tải lại</button>
        </footer>
      </div>`;
    document.body.appendChild(backdrop);
    const input = backdrop.querySelector('#key-input');
    input.value = apiKey();
    input.focus();
    backdrop.querySelector('#key-cancel').onclick = () => backdrop.remove();
    backdrop.querySelector('#key-save').onclick = () => {
        setApiKey(input.value);
        location.reload();
    };
    input.onkeydown = (e) => { if (e.key === 'Enter') backdrop.querySelector('#key-save').click(); };
}

/* ------------------------------------------------------------- Dinh dang */

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
    return v < 1000 ? v + ' ms' : (v / 1000).toFixed(1) + ' s';
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

function escapeHtml(text) {
    if (text === null || text === undefined) return '';
    return String(text)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

/**
 * Render Markdown toi gian (dam, nghieng, code, danh sach, bang, tieu de).
 * Co y KHONG dung thu vien ngoai: Artifact/CSP va moi truong noi bo deu khong
 * cho tai script tu CDN, va cau tra loi cua chatbot chi dung vai the co ban.
 * Moi noi dung deu duoc escape TRUOC khi them the -> khong co duong XSS.
 */
function renderMarkdown(src) {
    if (!src) return '';
    const lines = escapeHtml(src).split('\n');
    const out = [];
    let inList = false;
    let inTable = false;

    const closeBlocks = () => {
        if (inList) { out.push('</ul>'); inList = false; }
        if (inTable) { out.push('</tbody></table></div>'); inTable = false; }
    };

    for (let i = 0; i < lines.length; i++) {
        let line = lines[i];
        const trimmed = line.trim();

        if (!trimmed) { closeBlocks(); continue; }

        // Bang Markdown
        if (trimmed.startsWith('|')) {
            const cells = trimmed.replace(/^\||\|$/g, '').split('|').map(c => c.trim());
            const isSeparator = cells.every(c => /^:?-{2,}:?$/.test(c));
            if (isSeparator) continue;
            if (!inTable) {
                closeBlocks();
                out.push('<div class="scroll"><table><tbody>');
                inTable = true;
            }
            out.push('<tr>' + cells.map(c => '<td>' + inline(c) + '</td>').join('') + '</tr>');
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

        if (/^[-*+]\s+/.test(trimmed) || /^\d+[.)]\s+/.test(trimmed)) {
            if (!inList) { closeBlocks(); out.push('<ul>'); inList = true; }
            out.push('<li>' + inline(trimmed.replace(/^([-*+]|\d+[.)])\s+/, '')) + '</li>');
            continue;
        }

        if (trimmed.startsWith('&gt;')) {
            closeBlocks();
            out.push('<blockquote class="muted">' + inline(trimmed.slice(4).trim()) + '</blockquote>');
            continue;
        }

        if (inList) { out.push('</ul>'); inList = false; }
        out.push('<p>' + inline(trimmed) + '</p>');
    }
    closeBlocks();
    return out.join('\n');

    function inline(text) {
        return text
            .replace(/`([^`]+)`/g, '<code>$1</code>')
            .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
            .replace(/(^|\s)\*([^*]+)\*/g, '$1<em>$2</em>')
            .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g,
                '<a href="$2" target="_blank" rel="noopener">$1</a>');
    }
}

/* ---------------------------------------------------------------- Theme */

function initTheme() {
    const stored = localStorage.getItem('rag.theme');
    if (stored) document.documentElement.setAttribute('data-theme', stored);
    const btn = document.getElementById('theme-toggle');
    if (!btn) return;
    const sync = () => {
        const explicit = document.documentElement.getAttribute('data-theme');
        const dark = explicit
            ? explicit === 'dark'
            : window.matchMedia('(prefers-color-scheme: dark)').matches;
        btn.textContent = dark ? 'Sáng' : 'Tối';
        btn.title = dark ? 'Chuyển sang giao diện sáng' : 'Chuyển sang giao diện tối';
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

document.addEventListener('DOMContentLoaded', initTheme);
