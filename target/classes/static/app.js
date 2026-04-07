const state = {
    token: localStorage.getItem("auction_token") || "",
    session: JSON.parse(localStorage.getItem("auction_session") || "null"),
    csrfToken: localStorage.getItem("auction_csrf") || "",
    auctions: [],
    users: [],
    notifications: [],
    watchlist: [],
    invoices: [],
    selectedAuctionId: null,
    currentView: 'landing',
    activeTab: 'all',
    authMode: 'login',
    myAuctionsTab: 'all',
    historyTab: 'all',
    editingAuctionId: null,
    twoFaSecret: null,
    runtimeConfig: { wsPort: null }
};

function escapeHtml(str) {
    if (str == null) return '';
    return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#x27;');
}

const els = {
    app: document.getElementById("app"),
    mainNav: document.getElementById("mainNav"),
    guestNav: document.getElementById("guestNav"),
    userNav: document.getElementById("userNav"),
    userName: document.getElementById("userName"),
    userAvatar: document.getElementById("userAvatar"),
    adminLink: document.getElementById("adminLink"),
    sellerLink: document.getElementById("sellerLink"),
    toast: document.getElementById("toast"),
    logoutButton: document.getElementById("logoutButton"),
    notifBell: document.getElementById("notifBell"),
    notifBadge: document.getElementById("notifBadge"),
    authView: document.getElementById("authView"),
    authForm: document.getElementById("authForm"),
    authTitle: document.getElementById("authTitle"),
    authSubmit: document.getElementById("authSubmit"),
    authToggleLink: document.getElementById("authToggleLink"),
    authToggleText: document.getElementById("authToggleText"),
    latestGrid: document.getElementById("latestGrid"),
    fullGrid: document.getElementById("fullGrid"),
    adminGrid: document.getElementById("adminGrid"),
    myBidsGrid: document.getElementById("myBidsGrid"),
    statTotal: document.getElementById("statTotal"),
    statActive: document.getElementById("statActive"),
    statMine: document.getElementById("statMine"),
    statAdminActive: document.getElementById("statAdminActive"),
    statAdminTotal: document.getElementById("statAdminTotal"),
    statAdminUsers: document.getElementById("statAdminUsers"),
    statAdminRecent: document.getElementById("statAdminRecent"),
    userTableBody: document.getElementById("userTableBody"),
    detailView: document.getElementById("detailView"),
    detailTitle: document.getElementById("detailTitle"),
    detailDesc: document.getElementById("detailDesc"),
    detailPrice: document.getElementById("detailPrice"),
    detailStartPrice: document.getElementById("detailStartPrice"),
    detailBidCount: document.getElementById("detailBidCount"),
    detailCategory: document.getElementById("detailCategory"),
    detailImage: document.getElementById("detailImage"),
    bidHistoryList: document.getElementById("bidHistoryList"),
    watcherList: document.getElementById("watcherList"),
    sellerName: document.getElementById("sellerName"),
    sellerAvatar: document.getElementById("sellerAvatar"),
    winnerSection: document.getElementById("winnerSection"),
    timerSection: document.getElementById("timerSection"),
    bidActions: document.getElementById("bidActions"),
    endedMsg: document.getElementById("endedMsg"),
    searchInput: document.getElementById("searchInput"),
    categoryFilter: document.getElementById("categoryFilter")
};

const CATEGORY_META = {
    ELECTRONICS: { label: "Electronics", blurb: "Phones, gadgets, gaming gear, and connected tech." },
    ART: { label: "Art", blurb: "Original works, prints, and studio-made pieces." },
    VEHICLE: { label: "Vehicle", blurb: "Cars, bikes, specialty builds, and transport finds." },
    BOOK: { label: "Book", blurb: "Rare editions, sets, manga, and collectible reading." },
    FASHION: { label: "Fashion", blurb: "Designer apparel, watches, shoes, and accessories." },
    COLLECTIBLE: { label: "Collectible", blurb: "Cards, memorabilia, toys, trophies, and one-off pieces." },
    PROPERTY: { label: "Property", blurb: "Land, rental assets, spaces, and long-term holdings." }
};

const MAX_MONEY_AMOUNT = 1_000_000_000_000;
const APP_CONFIG = (() => {
    const rawConfig = window.AUCTION_APP_CONFIG || {};
    return {
        apiBaseUrl: normalizeBaseUrl(rawConfig.apiBaseUrl),
        assetBaseUrl: normalizeBaseUrl(rawConfig.assetBaseUrl),
        wsBaseUrl: normalizeWebSocketBaseUrl(rawConfig.wsBaseUrl)
    };
})();

function normalizeBaseUrl(value) {
    if (typeof value !== "string") {
        return "";
    }
    return value.trim().replace(/\/+$/, "");
}

function normalizeWebSocketBaseUrl(value) {
    return normalizeBaseUrl(value)
        .replace(/^http:/i, "ws:")
        .replace(/^https:/i, "wss:");
}

function isGitHubPagesDeployment() {
    return window.location.hostname.endsWith(".github.io");
}

function needsExternalBackendConfig() {
    return isGitHubPagesDeployment() && !APP_CONFIG.apiBaseUrl;
}

function resolveApiUrl(path) {
    if (/^https?:\/\//i.test(path)) {
        return path;
    }
    return APP_CONFIG.apiBaseUrl ? `${APP_CONFIG.apiBaseUrl}${path}` : path;
}

function resolveBackendOrigin() {
    if (!APP_CONFIG.apiBaseUrl) {
        return window.location.origin;
    }
    try {
        return new URL(APP_CONFIG.apiBaseUrl, window.location.origin).origin;
    } catch (_) {
        return window.location.origin;
    }
}

function resolveAssetUrl(path) {
    if (/^https?:\/\//i.test(path)) {
        return path;
    }
    const assetBase = APP_CONFIG.assetBaseUrl || resolveBackendOrigin();
    return assetBase ? `${assetBase}${path}` : path;
}

function showTwoFactorPrompt() {
    const group = document.getElementById("loginTwoFaGroup");
    const input = document.getElementById("loginTwoFaCode");
    group?.classList.remove("hidden");
    if (input) input.required = true;
    els.authSubmit.textContent = "Verify and Sign In";
}

function hideTwoFactorPrompt() {
    const group = document.getElementById("loginTwoFaGroup");
    const input = document.getElementById("loginTwoFaCode");
    group?.classList.add("hidden");
    if (input) {
        input.required = false;
        input.value = "";
    }
    els.authSubmit.textContent = state.authMode === "register" ? "Create Account" : "Sign In";
}

async function api(path, options = {}) {
    if (needsExternalBackendConfig()) {
        throw new Error("GitHub Pages is serving the frontend only. Set apiBaseUrl in config.js to your backend URL.");
    }
    const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
    if (state.token) headers.Authorization = `Bearer ${state.token}`;
    if (state.csrfToken && (options.method === "PUT" || options.method === "DELETE" || options.method === "POST" && !path.startsWith("/api/login") && !path.startsWith("/api/register") && !path.startsWith("/api/password-reset") && !path.startsWith("/api/account/verify") && !path.startsWith("/api/zalopay/callback"))) {
        headers["X-CSRF-Token"] = state.csrfToken;
    }
    const response = await fetch(resolveApiUrl(path), { ...options, headers });
    const rawBody = await response.text();
    let payload = null;
    if (rawBody) {
        try {
            payload = JSON.parse(rawBody);
        } catch (e) {
            if (!response.ok) {
                throw new Error(`Server returned ${response.status}: ${rawBody}`);
            }
            throw new Error(`Server returned ${response.status}: ${rawBody || 'Invalid response'}`);
        }
    }
    if (response.status === 401) {
        saveSession(null);
        renderNav();
        switchView('landing');
        throw new Error(payload?.message || "Session expired. Please sign in again.");
    }
    if (!payload) throw new Error(`Server returned ${response.status}: ${rawBody || 'Invalid response'}`);
    if (!payload.success) throw new Error(payload.message || "Request failed");
    return payload.data;
}

function saveSession(session) {
    state.session = session;
    state.token = session?.token || "";
    state.csrfToken = session?.csrfToken || "";
    if (state.token) {
        localStorage.setItem("auction_token", state.token);
        localStorage.setItem("auction_session", JSON.stringify(session));
        if (state.csrfToken) localStorage.setItem("auction_csrf", state.csrfToken);
    } else {
        localStorage.removeItem("auction_token");
        localStorage.removeItem("auction_session");
        localStorage.removeItem("auction_csrf");
    }
}

function notify(message, type = "info") {
    els.toast.textContent = message;
    els.toast.className = `toast ${type}`.trim();
    setTimeout(() => els.toast.className = "toast hidden", 3000);
}

function switchView(viewId) {
    state.currentView = viewId;
    els.mainNav?.classList.remove('mobile-open');
    els.guestNav?.classList.remove('mobile-open');
    els.userNav?.classList.remove('mobile-open');
    document.querySelectorAll('.view').forEach(v => v.classList.add('hidden'));
    const target = document.getElementById(viewId + 'View');
    if (target) target.classList.remove('hidden');
    document.querySelectorAll('.nav-link').forEach(link => {
        link.classList.toggle('active', link.dataset.view === viewId);
    });
    if (viewId === 'dashboard') renderDashboard();
    if (viewId === 'auctions') renderAuctions();
    if (viewId === 'categories') renderCategories();
    if (viewId === 'my-bids') renderMyBids();
    if (viewId === 'my-auctions') renderMyAuctions();
    if (viewId === 'watchlist') renderWatchlist();
    if (viewId === 'invoices') renderInvoices();
    if (viewId === 'history') renderHistory();
    if (viewId === 'admin') renderAdminDashboard();
    if (viewId === 'admin-refunds') renderAdminRefunds();
    if (viewId === 'notifications') renderNotifications();
    if (viewId === 'account') renderAccount();
}

function renderNav() {
    const loggedIn = Boolean(state.session);
    els.guestNav.classList.toggle("hidden", loggedIn);
    els.userNav.classList.toggle("hidden", !loggedIn);
    els.mainNav.classList.toggle("hidden", !loggedIn);
    if (loggedIn) {
        const username = state.session.username || '';
        els.userName.textContent = username.split(' ')[0];
        els.userAvatar.textContent = username.length > 0 ? username[0].toUpperCase() : '?';
        els.adminLink.classList.toggle("hidden", state.session.role !== "ADMIN");
        els.sellerLink?.classList.remove("hidden");
        loadNotifications();
    } else {
        els.sellerLink?.classList.add("hidden");
    }
}

function formatMoney(value, currency = "VND") {
    return new Intl.NumberFormat("vi-VN", { style: "currency", currency: currency, maximumFractionDigits: 0 }).format(value);
}

function formatCompactMoney(value, currency = "VND") {
    const amount = Number(value);
    if (!Number.isFinite(amount)) {
        return formatMoney(0, currency);
    }
    const abs = Math.abs(amount);
    const units = [
        { threshold: 1_000_000_000_000, suffix: "T" },
        { threshold: 1_000_000_000, suffix: "B" },
        { threshold: 1_000_000, suffix: "M" },
        { threshold: 1_000, suffix: "K" }
    ];
    const symbol = new Intl.NumberFormat("vi-VN", {
        style: "currency",
        currency,
        currencyDisplay: "narrowSymbol",
        maximumFractionDigits: 0
    }).formatToParts(0).find(part => part.type === "currency")?.value || `${currency} `;
    const unit = units.find(entry => abs >= entry.threshold);
    if (!unit) {
        return formatMoney(amount, currency);
    }
    const compactValue = amount / unit.threshold;
    const digits = compactValue >= 100 ? 0 : 1;
    return `${symbol}${compactValue.toFixed(digits).replace(/\.0$/, "")}${unit.suffix}`;
}

function isMarketplaceVisible(auction) {
    return auction?.state === 'OPEN' || auction?.state === 'RUNNING';
}

function getMarketplaceAuctions() {
    return state.auctions.filter(isMarketplaceVisible);
}

function parseMoneyInput(rawValue, label, { allowZero = false } = {}) {
    const amount = Number(rawValue);
    if (!Number.isFinite(amount) || (allowZero ? amount < 0 : amount <= 0)) {
        throw new Error(`${label} must be ${allowZero ? 'zero or greater' : 'greater than zero'}.`);
    }
    if (amount > MAX_MONEY_AMOUNT) {
        throw new Error(`${label} must not exceed ${formatMoney(MAX_MONEY_AMOUNT, "VND")}.`);
    }
    return amount;
}

function getIcon(type) {
    const icons = { ELECTRONICS: '📱', ART: '🎨', VEHICLE: '🚗', BOOK: '📚', FASHION: '👗', COLLECTIBLE: '🏆', PROPERTY: '🏠' };
    return icons[type?.toUpperCase()] || '📦';
}

function formatCategoryName(category) {
    const normalized = String(category || "").toUpperCase();
    return CATEGORY_META[normalized]?.label || (normalized.charAt(0) + normalized.slice(1).toLowerCase());
}

function categoryMark(category) {
    const label = formatCategoryName(category);
    return label.split(/\s+/).map(part => part[0]).join("").slice(0, 2).toUpperCase() || "CT";
}

function selectCategory(category) {
    if (els.categoryFilter) {
        els.categoryFilter.value = category || "All";
    }
    switchView('auctions');
    renderAuctions();
}

function createAuctionCard(auction, options = {}) {
    const isEnded = auction.state === 'FINISHED' || auction.state === 'CANCELED';
    const timeLabel = isEnded ? 'Ended' : relativeEndShort(auction.endTime);
    const imageHtml = auction.imageFilename ?
        `<img src="${escapeHtml(resolveAssetUrl(`/uploads/${encodeURIComponent(auction.imageFilename)}`))}" style="width:100%; height:100%; object-fit:cover;">` :
        getIcon(auction.itemType);
    const isWatching = state.watchlist.some(w => w.id === auction.id);
    return `
        <article class="auction-card" data-auction-id="${escapeHtml(auction.id)}">
            <div class="image-placeholder">
                ${imageHtml}
                <div class="tag-top-left">${escapeHtml(auction.itemType)}</div>
                ${isEnded ? '<div class="tag-top-right">Ended</div>' : `<div class="tag-bottom-right">${timeLabel} left</div>`}
                ${!isEnded ? `<button class="watch-icon-btn ${isWatching ? 'watching' : ''}" data-watch-id="${escapeHtml(auction.id)}" title="${isWatching ? 'Unwatch' : 'Watch'}">${isWatching ? '★' : '☆'}</button>` : ''}
            </div>
            <div class="auction-card-content">
                <p class="subtle eyebrow">by ${escapeHtml(auction.ownerUsername)}</p>
                <h3>${escapeHtml(auction.itemName)}</h3>
                <p class="subtle">${escapeHtml(auction.description)}</p>
                <div class="auction-card-footer">
                    <div class="bid-info">
                        <p class="subtle eyebrow">CURRENT BID</p>
                        <span class="bid-amount">${formatCompactMoney(auction.currentPrice, auction.currency)}</span>
                    </div>
                    <div class="bid-count">${auction.bidCount} bids</div>
                    <a href="#" class="link">View →</a>
                </div>
            </div>
        </article>
    `;
}

function relativeEndShort(timestamp) {
    const delta = timestamp - Date.now();
    if (delta <= 0) return "0s";
    const days = Math.floor(delta / (1000 * 60 * 60 * 24));
    if (days > 0) return `${days}d`;
    const hours = Math.floor(delta / (1000 * 60 * 60));
    if (hours > 0) return `${hours}h`;
    const minutes = Math.floor(delta / 60000);
    return `${minutes}m`;
}

function renderDashboard() {
    const sorted = [...getMarketplaceAuctions()].sort((a, b) => b.startTime - a.startTime);
    els.latestGrid.innerHTML = sorted.slice(0, 4).map(a => createAuctionCard(a)).join("");
    els.statTotal.textContent = state.auctions.length;
    els.statActive.textContent = state.auctions.filter(a => a.state === 'RUNNING').length;
    els.statMine.textContent = state.auctions.filter(a => a.ownerId === state.session?.userId).length;
}

function renderAuctions() {
    const search = els.searchInput?.value.toLowerCase() || "";
    const category = els.categoryFilter?.value || "All";
    const filtered = getMarketplaceAuctions().filter(a => {
        const matchesSearch = !search || a.itemName.toLowerCase().includes(search) || a.description.toLowerCase().includes(search);
        const matchesCategory = category === "All" || a.itemType.toUpperCase() === category.toUpperCase();
        return matchesSearch && matchesCategory;
    });
    if (els.fullGrid) {
        els.fullGrid.innerHTML = filtered.map(a => createAuctionCard(a)).join("");
    }
    const countEl = document.getElementById("auctionCount");
    if (countEl) countEl.textContent = `${filtered.length} items found`;
}

function renderCategories() {
    const grid = document.getElementById("categoriesGrid");
    const highlights = document.getElementById("categoriesHighlights");
    const summary = document.getElementById("categoriesSummary");
    if (!grid) return;

    const marketplaceAuctions = getMarketplaceAuctions();
    const knownCategories = new Set(Object.keys(CATEGORY_META));
    marketplaceAuctions.forEach(auction => {
        if (auction?.itemType) knownCategories.add(String(auction.itemType).toUpperCase());
    });

    const categories = [...knownCategories].map(category => {
        const allItems = marketplaceAuctions.filter(a => String(a.itemType || "").toUpperCase() === category);
        const activeItems = allItems
            .filter(a => a.state === 'RUNNING')
            .sort((a, b) => a.endTime - b.endTime)
            .slice(0, 2);
        return {
            category,
            label: formatCategoryName(category),
            blurb: CATEGORY_META[category]?.blurb || "Browse current listings in this category.",
            totalCount: allItems.length,
            activeCount: allItems.filter(a => a.state === 'RUNNING').length,
            previewItems: activeItems
        };
    }).sort((a, b) => b.activeCount - a.activeCount || b.totalCount - a.totalCount || a.label.localeCompare(b.label));

    const totalLive = categories.reduce((sum, category) => sum + category.activeCount, 0);
    const busiest = categories[0];
    if (summary) {
        summary.textContent = `${totalLive} live auctions across ${categories.length} categories. Start where the market is moving.`;
    }
    if (highlights) {
        highlights.innerHTML = `
            <div class="category-highlight">
                <span class="subtle">Live Listings</span>
                <span class="category-highlight-value">${totalLive}</span>
            </div>
            <div class="category-highlight">
                <span class="subtle">Busiest Category</span>
                <span class="category-highlight-value">${escapeHtml(busiest?.label || "None")}</span>
            </div>
            <div class="category-highlight">
                <span class="subtle">Catalog Size</span>
                <span class="category-highlight-value">${marketplaceAuctions.length}</span>
            </div>
        `;
    }

    grid.innerHTML = categories.map(category => `
        <article class="category-card">
            <div class="category-card-head">
                <div class="category-mark">${escapeHtml(categoryMark(category.category))}</div>
                <span class="category-balance">${category.activeCount} live</span>
            </div>
            <h3>${escapeHtml(category.label)}</h3>
            <p class="subtle">${escapeHtml(category.blurb)}</p>
            ${category.previewItems.length ? `
                <div class="category-preview-list">
                    ${category.previewItems.map(item => `
                        <div class="category-preview-item">
                            <span>${escapeHtml(item.itemName)}</span>
                            <strong>${formatCompactMoney(item.currentPrice, item.currency)}</strong>
                        </div>
                    `).join("")}
                </div>
            ` : `
                <div class="category-empty">No live auctions in this category right now.</div>
            `}
            <div class="category-card-footer">
                <p class="subtle">${category.totalCount} total listings</p>
                <button class="category-card-action" type="button" data-category-select="${escapeHtml(category.category)}">View category</button>
            </div>
        </article>
    `).join("");

    grid.querySelectorAll('[data-category-select]').forEach(button => {
        button.addEventListener('click', () => selectCategory(button.dataset.categorySelect));
    });
}

function renderMyBids() {
    const participated = state.auctions.filter(a =>
        a.bidHistory.some(b => b.bidderId === state.session?.userId)
    );
    const filtered = participated.filter(a => {
        if (state.activeTab === 'active') return a.state === 'RUNNING';
        if (state.activeTab === 'ended') return a.state === 'FINISHED';
        return true;
    });
    els.myBidsGrid.innerHTML = filtered.length ? filtered.map(a => createAuctionCard(a)).join("") : '<p class="subtle" style="text-align:center; padding:3rem 0;">You haven\'t bid on anything yet.</p>';
    const countEl = document.getElementById("myBidsCount");
    if (countEl) countEl.textContent = `All bids (${filtered.length})`;
}

function renderMyAuctions() {
    const myAuctions = state.auctions.filter(a => a.ownerId === state.session?.userId);
    const myTab = state.myAuctionsTab || 'all';
    const filtered = myAuctions.filter(a => {
        if (myTab === 'active') return a.state === 'RUNNING';
        if (myTab === 'ended') return a.state === 'FINISHED' || a.state === 'CANCELED';
        return true;
    });
    const countEl = document.getElementById("myAuctionsCount");
    if (countEl) countEl.textContent = `Your auctions (${filtered.length})`;
    const grid = document.getElementById("myAuctionsGrid");
    if (grid) grid.innerHTML = filtered.length ? filtered.map(a => createAuctionCard(a)).join("") : '<p class="subtle" style="text-align:center; padding:3rem 0;">You have no auctions yet.</p>';
}

async function renderWatchlist() {
    if (!state.token) return;
    try {
        state.watchlist = await api("/api/watchlist");
    } catch (e) { state.watchlist = []; }
    const grid = document.getElementById("watchlistGrid");
    const empty = document.getElementById("watchlistEmpty");
    if (state.watchlist.length === 0) {
        grid.innerHTML = "";
        empty.classList.remove("hidden");
    } else {
        empty.classList.add("hidden");
        grid.innerHTML = state.watchlist.map(a => createAuctionCard(a)).join("");
    }
}

async function renderInvoices() {
    if (!state.token) return;
    try {
        state.invoices = await api("/api/invoices");
    } catch (e) { state.invoices = []; }
    const tbody = document.getElementById("invoicesTableBody");
    const empty = document.getElementById("invoicesEmpty");
    if (state.invoices.length === 0) {
        tbody.innerHTML = "";
        empty.classList.remove("hidden");
    } else {
        empty.classList.add("hidden");
        tbody.innerHTML = state.invoices.map(inv => `
            <tr>
                <td>${escapeHtml(inv.id)}</td>
                <td>${escapeHtml(inv.auctionId)}</td>
                <td>${escapeHtml(inv.itemName)}</td>
                <td>${formatMoney(inv.amount, inv.currency)}</td>
                <td>${new Date(inv.createdAt).toLocaleDateString()}</td>
                <td><span class="status-pill">Paid</span></td>
            </tr>
        `).join("");
    }
}

function renderHistory() {
    const userId = state.session?.userId;
    const all = state.auctions.filter(a => {
        if (state.historyTab === 'bids') return a.bidHistory.some(b => b.bidderId === userId);
        if (state.historyTab === 'won') return a.state === 'FINISHED' && a.highestBidderId === userId;
        if (state.historyTab === 'sold') return a.ownerId === userId && (a.state === 'FINISHED' || a.state === 'CANCELED');
        return a.bidHistory.some(b => b.bidderId === userId) || a.ownerId === userId;
    });
    const grid = document.getElementById("historyGrid");
    const empty = document.getElementById("historyEmpty");
    if (all.length === 0) {
        grid.innerHTML = "";
        empty.classList.remove("hidden");
    } else {
        empty.classList.add("hidden");
        grid.innerHTML = all.map(a => {
            const myBid = a.bidHistory.find(b => b.bidderId === userId);
            const tag = a.ownerId === userId ? '<span class="tag-top-right" style="background:var(--success)">SOLD</span>' :
                        myBid ? '<span class="tag-top-right" style="background:var(--info)">BID</span>' : '';
            return createAuctionCard(a);
        }).join("");
    }
}

function renderAdminDashboard() {
    els.adminGrid.innerHTML = state.auctions.slice(0, 4).map(a => createAuctionCard(a)).join("");
    els.statAdminActive.textContent = state.auctions.filter(a => a.state === 'RUNNING').length;
    els.statAdminTotal.textContent = state.auctions.length;
    els.statAdminUsers.textContent = state.users.length;
    if (els.statAdminRecent) {
        const recentUsers = state.users.filter(user => user.createdAt && (Date.now() - Number(user.createdAt)) <= 7 * 24 * 60 * 60 * 1000);
        els.statAdminRecent.textContent = recentUsers.length || state.users.length;
    }
    const usersCount = document.getElementById("adminUsersCount");
    if (usersCount) usersCount.textContent = `${state.users.length} users`;
    if (els.userTableBody) {
        els.userTableBody.innerHTML = state.users.map(user => `
            <tr>
                <td>${escapeHtml(user.username)}</td>
                <td><span class="status-pill ${escapeHtml(user.role)}">${escapeHtml(user.role)}</span></td>
                <td>${user.auctionLimit}</td>
                <td class="actions-cell">
                    <button class="btn-secondary edit-limit-btn" data-username="${escapeHtml(user.username)}">Edit Limit</button>
                    <button class="btn-secondary delete-user-btn" data-username="${escapeHtml(user.username)}" style="color: var(--danger)">Delete</button>
                </td>
            </tr>
        `).join("");
        els.userTableBody.querySelectorAll('.edit-limit-btn').forEach(btn => {
            btn.addEventListener('click', () => updateLimit(btn.dataset.username));
        });
        els.userTableBody.querySelectorAll('.delete-user-btn').forEach(btn => {
            btn.addEventListener('click', () => deleteUser(btn.dataset.username));
        });
    }
    const adminRefundsLink = document.getElementById("adminRefundsLink");
    if (adminRefundsLink) adminRefundsLink.classList.toggle("hidden", state.session?.role !== "ADMIN");
}

async function renderAdminRefunds() {
    if (!state.token || state.session?.role !== "ADMIN") return;
    let refunds = [];
    try {
        refunds = await api("/api/admin/refunds");
    } catch (e) { refunds = []; }
    const tbody = document.getElementById("refundsTableBody");
    const empty = document.getElementById("refundsEmpty");
    if (!refunds || refunds.length === 0) {
        tbody.innerHTML = "";
        empty.classList.remove("hidden");
    } else {
        empty.classList.add("hidden");
        tbody.innerHTML = refunds.map(r => `
            <tr>
                <td>${escapeHtml(r.id)}</td>
                <td>${escapeHtml(r.auctionId || '-')}</td>
                <td>${escapeHtml(r.username || '-')}</td>
                <td>${formatMoney(r.amount || 0, 'VND')}</td>
                <td>${escapeHtml(r.reason || '-')}</td>
                <td><span class="status-pill ${escapeHtml(r.status || 'PENDING')}">${escapeHtml(r.status || 'PENDING')}</span></td>
                <td class="actions-cell">
                    ${(r.status === 'PENDING') ? `
                        <button class="btn-secondary approve-refund-btn" data-refund-id="${escapeHtml(r.id)}" style="color: var(--success); border-color: var(--success);">Approve</button>
                        <button class="btn-secondary reject-refund-btn" data-refund-id="${escapeHtml(r.id)}" style="color: var(--danger); border-color: var(--danger);">Reject</button>
                    ` : '-'}
                </td>
            </tr>
        `).join("");
        tbody.querySelectorAll('.approve-refund-btn').forEach(btn => {
            btn.addEventListener('click', async () => {
                try {
                    await api(`/api/admin/refunds/${btn.dataset.refundId}`, {
                        method: "PUT",
                        body: JSON.stringify({ action: "approve" })
                    });
                    notify("Refund approved", "success");
                    renderAdminRefunds();
                } catch (e) { notify(e.message, "danger"); }
            });
        });
        tbody.querySelectorAll('.reject-refund-btn').forEach(btn => {
            btn.addEventListener('click', async () => {
                try {
                    await api(`/api/admin/refunds/${btn.dataset.refundId}`, {
                        method: "PUT",
                        body: JSON.stringify({ action: "reject" })
                    });
                    notify("Refund rejected", "info");
                    renderAdminRefunds();
                } catch (e) { notify(e.message, "danger"); }
            });
        });
    }
}

async function renderNotifications() {
    if (!state.token) return;
    try {
        state.notifications = await api("/api/notifications");
    } catch (e) { state.notifications = []; }
    const list = document.getElementById("notificationsList");
    const empty = document.getElementById("notificationsEmpty");
    if (state.notifications.length === 0) {
        list.innerHTML = "";
        empty.classList.remove("hidden");
    } else {
        empty.classList.add("hidden");
        list.innerHTML = state.notifications.map(n => `
            <div class="notification-item ${n.read ? 'read' : 'unread'}" data-notif-id="${escapeHtml(n.id)}">
                <div class="notif-icon">${n.type === 'PAYMENT' ? '💰' : n.type === 'BID' ? '🔨' : n.type === 'AUCTION' ? '📦' : '🔔'}</div>
                <div class="notif-content">
                    <p>${escapeHtml(n.message)}</p>
                    <span class="subtle">${new Date(n.createdAt).toLocaleString()}</span>
                </div>
                ${!n.read ? `<button class="btn-secondary mark-read-btn" data-notif-id="${escapeHtml(n.id)}" style="font-size:0.75rem; padding:0.25rem 0.5rem;">Mark Read</button>` : ''}
            </div>
        `).join("");
        list.querySelectorAll('.mark-read-btn').forEach(btn => {
            btn.addEventListener('click', async () => {
                try {
                    await api(`/api/notifications/${btn.dataset.notifId}/read`, { method: "PUT" });
                    renderNotifications();
                    loadNotifications();
                } catch (e) { notify(e.message, "danger"); }
            });
        });
    }
}

function renderAccount() {
    const twoFaStatus = document.getElementById("twoFaStatus");
    const enableBtn = document.getElementById("enable2faBtn");
    const disableBtn = document.getElementById("disable2faBtn");
    const emailInput = document.getElementById("emailInput");
    const accountUserName = document.getElementById("accountUserName");
    const accountRoleLabel = document.getElementById("accountRoleLabel");
    const accountAvatar = document.getElementById("accountAvatar");
    if (emailInput && state.session?.email) {
        emailInput.value = state.session.email;
    }
    if (accountUserName && state.session?.username) {
        accountUserName.textContent = state.session.username;
    }
    if (accountRoleLabel) {
        accountRoleLabel.textContent = state.session?.role === "ADMIN" ? "Marketplace administrator" : "Marketplace member";
    }
    if (accountAvatar) {
        const username = state.session?.username || "A";
        accountAvatar.textContent = username.charAt(0).toUpperCase();
    }
    if (state.session?.twoFaEnabled) {
        twoFaStatus.textContent = "2FA is enabled";
        twoFaStatus.style.color = "var(--success)";
        enableBtn.classList.add("hidden");
        disableBtn.classList.remove("hidden");
    } else {
        twoFaStatus.textContent = "2FA is not enabled";
        twoFaStatus.style.color = "";
        enableBtn.classList.remove("hidden");
        disableBtn.classList.add("hidden");
    }
}

function openDetail(id) {
    state.selectedAuctionId = id;
    const auction = state.auctions.find(a => a.id === id);
    if (!auction) return;
    els.detailTitle.textContent = auction.itemName;
    els.detailDesc.textContent = auction.description;
    els.detailPrice.textContent = formatMoney(auction.currentPrice, auction.currency);
    els.detailStartPrice.textContent = `Started at ${formatMoney(auction.startingPrice, auction.currency)}`;
    els.detailBidCount.textContent = auction.bidCount;
    els.detailCategory.textContent = auction.itemType;
    els.sellerName.textContent = auction.ownerUsername;
    els.sellerAvatar.textContent = auction.ownerUsername.length > 0 ? auction.ownerUsername[0].toUpperCase() : '?';
    const imageHtml = auction.imageFilename ?
        `<img src="${escapeHtml(resolveAssetUrl(`/uploads/${encodeURIComponent(auction.imageFilename)}`))}" style="width:100%; height:100%; object-fit:cover; border-radius:20px;">` :
        getIcon(auction.itemType);
    els.detailImage.innerHTML = imageHtml;
    const isEnded = auction.state === 'FINISHED' || auction.state === 'CANCELED';
    els.timerSection.classList.toggle('hidden', isEnded);
    els.bidActions.classList.toggle('hidden', isEnded || auction.ownerId === state.session?.userId);
    els.endedMsg.classList.toggle('hidden', !isEnded);
    els.winnerSection.classList.toggle('hidden', !isEnded || !auction.highestBidderUsername);
    const editBtn = document.getElementById("editAuctionBtn");
    if (editBtn) {
        const canEdit = auction.ownerId === state.session?.userId && auction.bidCount === 0 && auction.state === 'RUNNING';
        editBtn.classList.toggle('hidden', !canEdit);
    }
    const deleteBtn = document.getElementById("deleteAuctionBtn");
    if (deleteBtn) {
        const canDelete = Boolean(state.session) && (auction.ownerId === state.session?.userId || state.session?.role === "ADMIN");
        deleteBtn.classList.toggle('hidden', !canDelete);
    }
    const watchBtn = document.getElementById("watchBtn");
    if (watchBtn) {
        const isWatching = state.watchlist.some(w => w.id === auction.id);
        watchBtn.textContent = isWatching ? 'Unwatch' : 'Watch';
        watchBtn.classList.toggle('hidden', !state.session || auction.ownerId === state.session?.userId);
    }
    if (isEnded && auction.highestBidderUsername) {
        document.getElementById("winnerName").textContent = auction.highestBidderUsername;
        document.getElementById("winnerBid").textContent = formatMoney(auction.currentPrice, auction.currency);
    }
    const paymentSection = document.getElementById("paymentSection");
    const payNowBtn = document.getElementById("payNowBtn");
    const markReceivedBtn = document.getElementById("markReceivedBtn");
    const paymentStatusText = document.getElementById("paymentStatusText");
    if (paymentSection) {
        const isWinner = auction.highestBidderId === state.session?.userId;
        const isOwner = auction.ownerId === state.session?.userId;
        if (isEnded && isWinner && !isOwner) {
            paymentSection.classList.remove("hidden");
            if (auction.fulfillmentStatus === 'paid' || auction.fulfillmentStatus === 'delivered') {
                paymentStatusText.textContent = "Payment confirmed";
                paymentStatusText.style.color = "var(--success)";
                payNowBtn.classList.add("hidden");
                markReceivedBtn.classList.toggle("hidden", auction.fulfillmentStatus === 'delivered');
            } else if (auction.fulfillmentStatus === 'awaiting_payment') {
                paymentStatusText.textContent = "Awaiting payment";
                paymentStatusText.style.color = "var(--warning)";
                payNowBtn.classList.remove("hidden");
                markReceivedBtn.classList.add("hidden");
            } else {
                paymentStatusText.textContent = "Payment not initiated";
                paymentStatusText.style.color = "";
                payNowBtn.classList.remove("hidden");
                markReceivedBtn.classList.add("hidden");
            }
        } else {
            paymentSection.classList.add("hidden");
        }
    }
    const refundSection = document.getElementById("refundSection");
    const requestRefundBtn = document.getElementById("requestRefundBtn");
    const refundStatusText = document.getElementById("refundStatusText");
    if (refundSection) {
        const isWinner = auction.highestBidderId === state.session?.userId;
        const isOwner = auction.ownerId === state.session?.userId;
        if (isEnded && isWinner && !isOwner && (auction.fulfillmentStatus === 'paid' || auction.fulfillmentStatus === 'delivered')) {
            refundSection.classList.remove("hidden");
            if (auction.fulfillmentStatus === 'paid') {
                refundStatusText.textContent = "You can request a refund if needed.";
                refundStatusText.style.color = "";
                requestRefundBtn.classList.remove("hidden");
            } else if (auction.fulfillmentStatus === 'delivered') {
                refundStatusText.textContent = "Item delivered. Refund requests may still be possible.";
                refundStatusText.style.color = "";
                requestRefundBtn.classList.remove("hidden");
            }
        } else {
            refundSection.classList.add("hidden");
        }
    }
    const buyNowSection = document.getElementById("buyNowSection");
    const buyNowBtn = document.getElementById("buyNowBtn");
    const buyNowPriceLabel = document.getElementById("buyNowPriceLabel");
    if (buyNowSection && buyNowBtn) {
        const isOwner = auction.ownerId === state.session?.userId;
        const isRunning = auction.state === 'RUNNING';
        if (isRunning && !isOwner && auction.buyItNowPrice > 0) {
            buyNowSection.classList.remove("hidden");
            buyNowPriceLabel.textContent = formatMoney(auction.buyItNowPrice, auction.currency);
        } else {
            buyNowSection.classList.add("hidden");
        }
    }
    els.bidHistoryList.innerHTML = auction.bidHistory.length ?
        [...auction.bidHistory].reverse().map((bid, i) => `
            <div class="bid-item">
                <div class="bidder-meta">
                    <div class="avatar">${escapeHtml((bid.bidderUsername || '?')[0].toUpperCase())}</div>
                    <div>
                        <strong>${escapeHtml(bid.bidderUsername)}</strong> ${i === 0 ? '<span class="leading-badge">LEADING</span>' : ''}
                        <div class="subtle">${new Date(bid.timestamp).toLocaleString()}</div>
                    </div>
                </div>
                <strong>${formatMoney(bid.amount, auction.currency)}</strong>
            </div>
        `).join("") : '<p class="subtle">No bids yet.</p>';
    els.watcherList.innerHTML = Array.from({length: Math.min(auction.watchingCount, 3)}).map(() => `
        <div class="avatar" title="User">U</div>
    `).join("") + (auction.watchingCount > 3 ? `<span class="subtle">+${auction.watchingCount - 3} more</span>` : '');
    switchView('detail');
    updateCountdown();
}

function updateCountdown() {
    if (state.currentView !== 'detail' || !state.selectedAuctionId) return;
    const auction = state.auctions.find(a => a.id === state.selectedAuctionId);
    if (!auction || auction.state === 'FINISHED') return;
    const delta = auction.endTime - Date.now();
    if (delta <= 0) { loadAuctions(); return; }
    const d = Math.floor(delta / (1000 * 60 * 60 * 24));
    const h = Math.floor((delta % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));
    const m = Math.floor((delta % (1000 * 60 * 60)) / (1000 * 60));
    const s = Math.floor((delta % (1000 * 60)) / 1000);
    const daysEl = document.getElementById("days");
    const hoursEl = document.getElementById("hours");
    const minsEl = document.getElementById("mins");
    const secsEl = document.getElementById("secs");
    if (daysEl) daysEl.textContent = d.toString().padStart(2, '0');
    if (hoursEl) hoursEl.textContent = h.toString().padStart(2, '0');
    if (minsEl) minsEl.textContent = m.toString().padStart(2, '0');
    if (secsEl) secsEl.textContent = s.toString().padStart(2, '0');
}

async function loadNotifications() {
    if (!state.token) return;
    try {
        const notifs = await api("/api/notifications");
        const unread = notifs.filter(n => !n.read).length;
        if (els.notifBadge) {
            if (unread > 0) {
                els.notifBadge.textContent = unread > 9 ? '9+' : unread;
                els.notifBadge.classList.remove("hidden");
            } else {
                els.notifBadge.classList.add("hidden");
            }
        }
    } catch (e) {}
}

document.querySelectorAll('[data-view]').forEach(el => {
    el.addEventListener('click', e => {
        e.preventDefault();
        switchView(el.dataset.view);
    });
});

document.getElementById("goHome").addEventListener('click', () => {
    if (!state.session) { switchView('landing'); } else { switchView('dashboard'); }
});

document.getElementById("mobileMenuBtn")?.addEventListener('click', () => {
    if (state.session) {
        els.mainNav?.classList.toggle('mobile-open');
        els.userNav?.classList.toggle('mobile-open');
        return;
    }
    els.guestNav?.classList.toggle('mobile-open');
});

document.getElementById("openLogin").addEventListener('click', () => openAuth('login'));
document.getElementById("openRegister").addEventListener('click', () => openAuth('register'));
document.getElementById("heroSignIn").addEventListener('click', () => openAuth('login'));
document.getElementById("heroStart").addEventListener('click', () => openAuth('register'));
document.getElementById("detailBack").addEventListener('click', () => switchView('auctions'));

document.getElementById("notifBell")?.addEventListener('click', () => {
    switchView('notifications');
});

document.getElementById("openAccountBtn")?.addEventListener('click', () => {
    if (state.session) {
        switchView('account');
    }
});

document.getElementById("markAllReadBtn")?.addEventListener('click', async () => {
    try {
        for (const n of state.notifications.filter(n => !n.read)) {
            await api(`/api/notifications/${n.id}/read`, { method: "PUT" });
        }
        renderNotifications();
        loadNotifications();
        notify("All notifications marked as read", "success");
    } catch (e) { notify(e.message, "danger"); }
});

document.getElementById("browseAllCategoriesBtn")?.addEventListener('click', () => {
    selectCategory("All");
});

function openAuth(mode) {
    state.authMode = mode;
    const isReg = mode === 'register';
    els.authTitle.textContent = isReg ? 'Create Account' : 'Sign In';
    els.authSubmit.textContent = isReg ? 'Create Account' : 'Sign In';
    els.authToggleText.textContent = isReg ? 'Already have an account?' : "Don't have an account?";
    els.authToggleLink.textContent = isReg ? 'Sign in' : 'Sign up';
    hideTwoFactorPrompt();
    fetchCaptcha();
    switchView('auth');
}

async function fetchCaptcha() {
    try {
        const res = await api("/api/captcha");
        document.getElementById("captchaLabel").textContent = res.question;
        document.getElementById("captchaChallengeId").value = res.challengeId;
        document.getElementById("captchaAnswer").value = "";
    } catch (e) {
        document.getElementById("captchaLabel").textContent = "What is 6 + 4?";
        document.getElementById("captchaChallengeId").value = "stub";
    }
}

els.authToggleLink.addEventListener('click', e => {
    e.preventDefault();
    openAuth(state.authMode === 'login' ? 'register' : 'login');
});

document.getElementById("forgotPasswordLink")?.addEventListener('click', async e => {
    e.preventDefault();
    const currentUsername = els.authForm?.elements?.username?.value || "";
    const username = prompt("Enter your username to receive a password reset link:", currentUsername);
    if (!username) return;
    try {
        await api("/api/password-reset/request", {
            method: "POST",
            body: JSON.stringify({ username })
        });
        notify("If the account exists, a reset link has been sent.", "info");
    } catch (err) {
        notify(err.message, "danger");
    }
});

els.authForm.addEventListener('submit', async e => {
    e.preventDefault();
    const formData = new FormData(e.target);
    const data = Object.fromEntries(formData.entries());
    data.captchaChallengeId = document.getElementById("captchaChallengeId").value;
    data.captchaAnswer = Number(document.getElementById("captchaAnswer").value);
    const isReg = state.authMode === 'register';
    try {
        const session = await api(isReg ? "/api/register" : "/api/login", {
            method: "POST",
            body: JSON.stringify(data)
        });
        hideTwoFactorPrompt();
        saveSession(session);
        renderNav();
        switchView('dashboard');
        notify(isReg ? "Account created!" : "Welcome back!", "success");
        await loadAuctions();
    } catch (err) {
        if (!isReg && err.message === "2FA_REQUIRED") {
            showTwoFactorPrompt();
            await fetchCaptcha();
            notify("Enter your authenticator code to finish signing in.", "info");
            return;
        }
        notify(err.message, "danger");
    }
});

els.logoutButton.addEventListener('click', async () => {
    try { await api("/api/logout", { method: "POST" }); } catch(_) {}
    saveSession(null);
    renderNav();
    switchView('landing');
    notify("Logged out successfully");
});

document.getElementById("placeBidBtn").addEventListener('click', async () => {
    let amount;
    try {
        amount = parseMoneyInput(document.getElementById("bidInput").value, "Bid amount");
    } catch (err) {
        notify(err.message, "danger");
        return;
    }
    if (!state.token) { notify("Please sign in to place a bid", "danger"); return; }
    try {
        await api(`/api/auctions/${state.selectedAuctionId}/bid`, {
            method: "POST",
            body: JSON.stringify({ amount })
        });
        notify("Bid placed successfully!", "success");
        document.getElementById("bidInput").value = "";
        await loadAuctions();
        openDetail(state.selectedAuctionId);
    } catch (err) { notify(err.message, "danger"); }
});

document.getElementById("autoBidBtn").addEventListener('click', async () => {
    let maxBid;
    let increment;
    try {
        maxBid = parseMoneyInput(document.getElementById("autoMaxInput").value, "Auto-bid maximum");
        increment = parseMoneyInput(document.getElementById("autoIncInput").value, "Auto-bid increment");
    } catch (err) {
        notify(err.message, "danger");
        return;
    }
    try {
        await api(`/api/auctions/${state.selectedAuctionId}/autobid`, {
            method: "POST",
            body: JSON.stringify({ maxBid, increment })
        });
        notify("Auto-bid configured", "success");
        await loadAuctions();
    } catch (err) { notify(err.message, "danger"); }
});

document.getElementById("watchBtn")?.addEventListener('click', async () => {
    const auctionId = state.selectedAuctionId;
    if (!auctionId) return;
    const isWatching = state.watchlist.some(w => w.id === auctionId);
    try {
        if (isWatching) {
            await api(`/api/watchlist/${auctionId}`, { method: "DELETE" });
            notify("Removed from watchlist", "info");
        } else {
            await api("/api/watchlist", { method: "POST", body: JSON.stringify({ auctionId }) });
            notify("Added to watchlist", "success");
        }
        await loadAuctions();
        openDetail(auctionId);
    } catch (err) { notify(err.message, "danger"); }
});

document.getElementById("buyNowBtn")?.addEventListener('click', async () => {
    const auctionId = state.selectedAuctionId;
    if (!auctionId) return;
    if (!state.token) {
        notify("Please sign in to use Buy It Now", "danger");
        openAuth('login');
        return;
    }
    const auction = state.auctions.find(a => a.id === auctionId);
    if (!auction) return;
    const confirmed = window.confirm(`Buy "${auction.itemName}" now for ${formatMoney(auction.buyItNowPrice, auction.currency)}? This will end the auction immediately.`);
    if (!confirmed) return;
    try {
        const result = await api(`/api/auctions/${auctionId}/buy-now`, { method: "POST" });
        if (result.orderUrl) {
            notify("Redirecting to ZaloPay...", "info");
            window.location.href = result.orderUrl;
            return;
        }
        notify(result.message || "Buy It Now initiated", "success");
        await loadAuctions();
        openDetail(auctionId);
    } catch (err) { notify(err.message, "danger"); }
});

document.getElementById("payNowBtn")?.addEventListener('click', async () => {
    const auctionId = state.selectedAuctionId;
    if (!auctionId) return;
    const auction = state.auctions.find(a => a.id === auctionId);
    if (!auction) return;
    try {
        const result = await api(`/api/auctions/${auctionId}/pay`, {
            method: "POST",
            body: JSON.stringify({ amount: auction.currentPrice })
        });
        if (result.orderUrl) {
            notify("Redirecting to ZaloPay...", "info");
            window.location.href = result.orderUrl;
        } else {
            notify("Payment initiated: " + (result.message || "Please wait for confirmation"), "success");
            await loadAuctions();
            openDetail(auctionId);
        }
    } catch (err) { notify(err.message, "danger"); }
});

document.getElementById("markReceivedBtn")?.addEventListener('click', async () => {
    const auctionId = state.selectedAuctionId;
    if (!auctionId) return;
    try {
        await api(`/api/auctions/${auctionId}/mark-received`, { method: "POST" });
        notify("Item marked as received!", "success");
        await loadAuctions();
        openDetail(auctionId);
    } catch (err) { notify(err.message, "danger"); }
});

document.getElementById("requestRefundBtn")?.addEventListener('click', async () => {
    const auctionId = state.selectedAuctionId;
    if (!auctionId) return;
    const reason = prompt("Enter refund reason:");
    if (!reason) return;
    try {
        await api(`/api/auctions/${auctionId}/refund`, {
            method: "POST",
            body: JSON.stringify({ reason })
        });
        notify("Refund request submitted!", "success");
        await loadAuctions();
        openDetail(auctionId);
    } catch (err) { notify(err.message, "danger"); }
});

document.getElementById("deleteAuctionBtn")?.addEventListener('click', async () => {
    const auctionId = state.selectedAuctionId;
    const auction = state.auctions.find(a => a.id === auctionId);
    if (!auctionId || !auction) return;
    const confirmed = window.confirm(`Delete "${auction.itemName}" permanently? This cannot be undone.`);
    if (!confirmed) return;
    try {
        await api(`/api/auctions/${auctionId}`, { method: "DELETE" });
        notify("Item deleted", "success");
        await loadAuctions();
        const nextView = auction.ownerId === state.session?.userId ? 'my-auctions' : (state.session?.role === "ADMIN" ? 'admin' : 'auctions');
        switchView(nextView);
    } catch (err) { notify(err.message, "danger"); }
});

function openEdit() {
    const auction = state.auctions.find(a => a.id === state.selectedAuctionId);
    if (!auction) return;
    state.editingAuctionId = auction.id;
    document.getElementById("auctionFormTitle").textContent = "Edit Auction";
    document.getElementById("submitAuctionBtn").textContent = "Update Auction";
    const form = document.getElementById("auctionForm");
    form.elements["name"].value = auction.itemName;
    form.elements["description"].value = auction.description;
    form.elements["itemType"].value = auction.itemType;
    form.elements["startingPrice"].value = auction.startingPrice;
    const delta = auction.endTime - Date.now();
    const remainingMins = Math.max(1, Math.floor(delta / 60000));
    form.elements["durationMinutes"].value = remainingMins;
    if (form.elements["currency"]) form.elements["currency"].value = auction.currency || "VND";
    if (form.elements["extraInfo"]) form.elements["extraInfo"].value = auction.extraValue || "";
    if (form.elements["buyItNowPrice"]) form.elements["buyItNowPrice"].value = auction.buyItNowPrice || 0;
    if (form.elements["reservePrice"]) form.elements["reservePrice"].value = auction.reservePrice || 0;
    switchView('create');
}

window.openDetail = openDetail;
window.deleteUser = deleteUser;
window.updateLimit = updateLimit;
window.openEdit = openEdit;

document.getElementById("auctionForm").addEventListener('submit', async e => {
    e.preventDefault();
    const formData = new FormData(e.target);
    const body = Object.fromEntries(formData.entries());
    try {
        body.startingPrice = parseMoneyInput(body.startingPrice, "Starting price");
        body.buyItNowPrice = parseMoneyInput(body.buyItNowPrice || 0, "Buy-it-now price", { allowZero: true });
        body.reservePrice = parseMoneyInput(body.reservePrice || 0, "Reserve price", { allowZero: true });
    } catch (err) {
        notify(err.message, "danger");
        return;
    }
    body.durationMinutes = Number(body.durationMinutes);
    if (!body.extraInfo) delete body.extraInfo;
    const rawScheduledStartTime = body.scheduledStartTime;
    delete body.scheduledStartTime;
    if (rawScheduledStartTime) {
        const scheduledStartTime = new Date(rawScheduledStartTime).getTime();
        if (!Number.isFinite(scheduledStartTime)) {
            notify("Invalid scheduled start time", "danger");
            return;
        }
        body.scheduledStartTime = scheduledStartTime;
    }
    const fileInput = document.getElementById("imageInput");
    if (fileInput && fileInput.files.length > 0) {
        try {
            const file = fileInput.files[0];
            const buffer = await file.arrayBuffer();
            const uploadResult = await api("/api/upload", {
                method: "POST",
                body: buffer,
                headers: {
                    "Content-Type": "application/octet-stream",
                    "Authorization": `Bearer ${state.token}`
                }
            });
            body.imageFilename = uploadResult.filename;
        } catch (err) {
            notify("Image upload failed: " + err.message, "danger");
            return;
        }
    }
    try {
        let endpoint = "/api/auctions";
        let method = "POST";
        if (state.editingAuctionId) {
            endpoint = `/api/auctions/${state.editingAuctionId}`;
            method = "PUT";
        }
        await api(endpoint, { method: method, body: JSON.stringify(body) });
        notify(state.editingAuctionId ? "Auction updated successfully!" : "Auction created successfully!", "success");
        state.editingAuctionId = null;
        document.getElementById("auctionFormTitle").textContent = "Create New Auction";
        document.getElementById("submitAuctionBtn").textContent = "Create Auction";
        e.target.reset();
        await loadAuctions();
        switchView('dashboard');
    } catch (err) { notify(err.message, "danger"); }
});

document.getElementById("passwordForm")?.addEventListener('submit', async e => {
    e.preventDefault();
    const formData = new FormData(e.target);
    try {
        await api("/api/account/password", {
            method: "PUT",
            body: JSON.stringify(Object.fromEntries(formData.entries()))
        });
        notify("Password updated successfully!", "success");
        e.target.reset();
    } catch (err) { notify(err.message, "danger"); }
});

document.getElementById("emailForm")?.addEventListener('submit', async e => {
    e.preventDefault();
    const formData = new FormData(e.target);
    try {
        await api("/api/account/email", {
            method: "PUT",
            body: JSON.stringify(Object.fromEntries(formData.entries()))
        });
        notify("Verification email sent.", "success");
    } catch (err) {
        notify(err.message, "danger");
    }
});

document.getElementById("deleteAccountBtn")?.addEventListener('click', async () => {
    const password = prompt("Enter your password to confirm account deletion:");
    if (!password) return;
    if (!confirm("This action is permanent and cannot be undone. Continue?")) return;
    try {
        await api("/api/account", { method: "DELETE", body: JSON.stringify({ password }) });
        saveSession(null);
        renderNav();
        switchView('landing');
        notify("Account deleted successfully", "success");
    } catch (err) { notify(err.message, "danger"); }
});

document.getElementById("enable2faBtn")?.addEventListener('click', async () => {
    try {
        const setup = await api("/api/account/2fa/setup", { method: "POST" });
        state.twoFaSecret = setup.secret;
        document.getElementById("twoFaSetup").classList.remove("hidden");
        document.getElementById("twoFaQrCode").innerHTML = `<p class="subtle">Secret: <code>${escapeHtml(setup.secret)}</code></p>`;
    } catch (err) { notify(err.message, "danger"); }
});

document.getElementById("confirm2faBtn")?.addEventListener('click', async () => {
    const code = document.getElementById("twoFaCodeInput").value;
    if (!code || code.length !== 6) { notify("Enter a valid 6-digit code", "danger"); return; }
    try {
        await api("/api/account/2fa/enable", { method: "POST", body: JSON.stringify({ code }) });
        if (state.session) {
            state.session.twoFaEnabled = true;
            saveSession(state.session);
        }
        notify("2FA enabled!", "success");
        document.getElementById("twoFaSetup").classList.add("hidden");
        renderAccount();
    } catch (err) { notify(err.message, "danger"); }
});

document.getElementById("disable2faBtn")?.addEventListener('click', async () => {
    const code = prompt("Enter your current 2FA code to disable:");
    if (!code) return;
    try {
        await api("/api/account/2fa/disable", { method: "POST", body: JSON.stringify({ code }) });
        if (state.session) {
            state.session.twoFaEnabled = false;
            saveSession(state.session);
        }
        notify("2FA disabled", "info");
        renderAccount();
    } catch (err) { notify(err.message, "danger"); }
});

document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => {
        document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        if (tab.dataset.filter) { state.activeTab = tab.dataset.filter; renderMyBids(); }
        if (tab.dataset.myFilter) { state.myAuctionsTab = tab.dataset.myFilter; renderMyAuctions(); }
        if (tab.dataset.historyFilter) { state.historyTab = tab.dataset.historyFilter; renderHistory(); }
    });
});

els.searchInput?.addEventListener('input', () => { if (state.currentView === 'auctions') renderAuctions(); });
els.categoryFilter?.addEventListener('change', () => { if (state.currentView === 'auctions') renderAuctions(); });

document.addEventListener('click', (e) => {
    const watchBtn = e.target.closest('.watch-icon-btn');
    if (watchBtn) {
        e.preventDefault();
        e.stopPropagation();
        const auctionId = watchBtn.dataset.watchId;
        const isWatching = watchBtn.classList.contains('watching');
        api(isWatching ? `/api/watchlist/${auctionId}` : "/api/watchlist", {
            method: isWatching ? "DELETE" : "POST",
            body: isWatching ? null : JSON.stringify({ auctionId })
        }).then(() => {
            notify(isWatching ? "Removed from watchlist" : "Added to watchlist", "success");
            loadAuctions();
            if (state.currentView === 'watchlist') renderWatchlist();
        }).catch(err => notify(err.message, "danger"));
    }
    const card = e.target.closest('.auction-card');
    if (card && !e.target.closest('.watch-icon-btn') && !e.target.closest('a') && !e.target.closest('button')) {
        e.preventDefault();
        openDetail(card.dataset.auctionId);
    }
});

function initWebSocket() {
    if (needsExternalBackendConfig()) {
        return;
    }
    let reloadTimeout = null;
    const ws = new WebSocket(resolveWebSocketUrl());
    ws.onopen = () => {
        if (state.token) ws.send("AUTH:" + state.token);
        console.log("Connected to auction updates");
    };
    ws.onmessage = (event) => {
        console.log("Update received:", event.data);
        if (reloadTimeout) clearTimeout(reloadTimeout);
        reloadTimeout = setTimeout(() => {
            loadAuctions();
            loadNotifications();
            if (state.currentView === 'detail' && state.selectedAuctionId) openDetail(state.selectedAuctionId);
            if (state.currentView === 'watchlist') renderWatchlist();
        }, 500);
    };
    ws.onclose = () => { console.log("WebSocket disconnected. Retrying in 5s..."); setTimeout(initWebSocket, 5000); };
    ws.onerror = (err) => console.error("WebSocket error:", err);
}

async function loadAuctions() {
    const result = await api("/api/auctions");
    state.auctions = Array.isArray(result) ? result : (result.auctions || result.items || []);
    if (state.currentView === 'detail' && state.selectedAuctionId && !state.auctions.some(a => a.id === state.selectedAuctionId)) {
        state.selectedAuctionId = null;
        switchView('auctions');
        notify("This listing is no longer available.", "info");
        return;
    }
    if (state.session?.role === "ADMIN") {
        state.users = await api("/api/users");
    }
    if (state.currentView === 'dashboard') renderDashboard();
    if (state.currentView === 'auctions') renderAuctions();
    if (state.currentView === 'categories') renderCategories();
    if (state.currentView === 'my-bids') renderMyBids();
    if (state.currentView === 'my-auctions') renderMyAuctions();
    if (state.currentView === 'admin') renderAdminDashboard();
}

async function deleteUser(username) {
    if (!confirm(`Are you sure you want to delete user ${username}?`)) return;
    try {
        state.users = await api(`/api/users/${encodeURIComponent(username)}`, { method: "DELETE" });
        notify("User deleted", "success");
        renderAdminDashboard();
    } catch (err) { notify(err.message, "danger"); }
}

async function updateLimit(username) {
    const limit = prompt("Enter new auction limit:", "5");
    if (limit === null) return;
    const parsedLimit = Number.parseInt(limit, 10);
    if (!Number.isInteger(parsedLimit) || parsedLimit < 0) {
        notify("Enter a valid non-negative auction limit", "danger");
        return;
    }
    try {
        state.users = await api(`/api/users/${encodeURIComponent(username)}/auction-limit`, {
            method: "PUT",
            body: JSON.stringify({ limit: parsedLimit })
        });
        notify("Limit updated", "success");
        renderAdminDashboard();
    } catch (err) { notify(err.message, "danger"); }
}

async function init() {
    renderNav();
    if (needsExternalBackendConfig()) {
        notify("Pages deployment is live. Set config.js apiBaseUrl to connect your backend.", "info");
    }
    await fetchRuntimeConfig();
    initWebSocket();
    await handleActionLinks();
    if (state.session && state.session.token) {
        try {
            const csrfResp = await api("/api/csrf");
            state.csrfToken = csrfResp.csrfToken;
            if (state.csrfToken) localStorage.setItem("auction_csrf", state.csrfToken);
        } catch (err) {}
    }
    if (state.session) {
        try {
            await loadAuctions();
            switchView('dashboard');
        } catch (err) {
            console.error("Session initialization failed:", err);
            saveSession(null);
            renderNav();
            switchView('landing');
        }
    } else {
        switchView('landing');
    }
    let countdownInterval = setInterval(updateCountdown, 1000);
    window.addEventListener('beforeunload', () => clearInterval(countdownInterval));
    const origSwitchView = switchView;
    window.switchView = function(view) {
        origSwitchView(view);
        if (view !== 'detail') {
            clearInterval(countdownInterval);
            countdownInterval = setInterval(updateCountdown, 1000);
        }
    };
}

async function fetchRuntimeConfig() {
    if (needsExternalBackendConfig()) {
        state.runtimeConfig = { wsPort: null };
        return;
    }
    try {
        state.runtimeConfig = await api("/api/config");
    } catch (_) {
        state.runtimeConfig = { wsPort: null };
    }
}

function resolveWebSocketUrl() {
    if (APP_CONFIG.wsBaseUrl) {
        return APP_CONFIG.wsBaseUrl;
    }
    const backendOrigin = resolveBackendOrigin();
    const backendUrl = new URL(backendOrigin);
    const wsProtocol = backendUrl.protocol === "https:" ? "wss:" : "ws:";
    const configuredPort = Number(state.runtimeConfig?.wsPort || 0);
    const defaultPort = wsProtocol === "wss:" ? 443 : 80;
    const currentPort = backendUrl.port ? Number(backendUrl.port) : defaultPort;
    const host = configuredPort && configuredPort !== currentPort
        ? `${backendUrl.hostname}:${configuredPort}`
        : backendUrl.host;
    return `${wsProtocol}//${host}`;
}

async function handleActionLinks() {
    const params = new URLSearchParams(window.location.search);
    const action = params.get("action");
    const token = params.get("token");
    if (!action || !token) return;

    try {
        if (action === "verify-email") {
            const session = await api("/api/account/verify-email", {
                method: "POST",
                body: JSON.stringify({ token })
            });
            saveSession(session);
            renderNav();
            notify("Email verified successfully.", "success");
        } else if (action === "reset-password") {
            const password = prompt("Enter a new password for your account:");
            if (!password) return;
            const session = await api("/api/password-reset/confirm", {
                method: "POST",
                body: JSON.stringify({ token, newPassword: password })
            });
            saveSession(session);
            renderNav();
            notify("Password reset successfully.", "success");
        }
    } catch (err) {
        notify(err.message, "danger");
    } finally {
        history.replaceState({}, document.title, window.location.pathname);
    }
}

init();
