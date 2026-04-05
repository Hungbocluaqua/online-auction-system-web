const state = {
    token: localStorage.getItem("auction_token") || "",
    session: JSON.parse(localStorage.getItem("auction_session") || "null"),
    auctions: [],
    users: [],
    selectedAuctionId: null,
    lastEventTime: Date.now(),
    currentView: 'landing',
    activeTab: 'all'
};

const els = {
    app: document.getElementById("app"),
    mainNav: document.getElementById("mainNav"),
    guestNav: document.getElementById("guestNav"),
    userNav: document.getElementById("userNav"),
    userName: document.getElementById("userName"),
    userAvatar: document.getElementById("userAvatar"),
    adminLink: document.getElementById("adminLink"),
    toast: document.getElementById("toast"),
    
    // Auth
    authView: document.getElementById("authView"),
    authForm: document.getElementById("authForm"),
    authTitle: document.getElementById("authTitle"),
    authSubmit: document.getElementById("authSubmit"),
    roleGroup: document.getElementById("roleGroup"),
    authToggleLink: document.getElementById("authToggleLink"),
    authToggleText: document.getElementById("authToggleText"),

    // Grids
    latestGrid: document.getElementById("latestGrid"),
    fullGrid: document.getElementById("fullGrid"),
    adminGrid: document.getElementById("adminGrid"),
    myBidsGrid: document.getElementById("myBidsGrid"),
    
    // Stats
    statTotal: document.getElementById("statTotal"),
    statActive: document.getElementById("statActive"),
    statMine: document.getElementById("statMine"),
    statAdminActive: document.getElementById("statAdminActive"),
    statAdminTotal: document.getElementById("statAdminTotal"),
    statAdminUsers: document.getElementById("statAdminUsers"),
    statAdminRecent: document.getElementById("statAdminRecent"),
    
    // Detail
    detailView: document.getElementById("detailView"),
    detailTitle: document.getElementById("detailTitle"),
    detailDesc: document.getElementById("detailDesc"),
    detailPrice: document.getElementById("detailPrice"),
    detailStartPrice: document.getElementById("detailStartPrice"),
    detailBidCount: document.getElementById("detailBidCount"),
    detailCategory: document.getElementById("detailCategory"),
    detailImage: document.getElementById("detailImage"),
    detailStateOverlay: document.getElementById("detailStateOverlay"),
    bidHistoryList: document.getElementById("bidHistoryList"),
    watcherList: document.getElementById("watcherList"),
    sellerName: document.getElementById("sellerName"),
    sellerAvatar: document.getElementById("sellerAvatar"),
    winnerSection: document.getElementById("winnerSection"),
    timerSection: document.getElementById("timerSection"),
    bidActions: document.getElementById("bidActions"),
    endedMsg: document.getElementById("endedMsg"),
    
    // Global inputs
    searchInput: document.getElementById("searchInput"),
    categoryFilter: document.getElementById("categoryFilter")
};

// --- API & State Management ---

async function api(path, options = {}) {
    const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
    if (state.token) headers.Authorization = `Bearer ${state.token}`;

    const response = await fetch(path, { ...options, headers });
    const payload = await response.json();
    if (!payload.success) throw new Error(payload.message || "Request failed");
    return payload.data;
}

function saveSession(session) {
    state.session = session;
    state.token = session?.token || "";
    if (state.token) {
        localStorage.setItem("auction_token", state.token);
        localStorage.setItem("auction_session", JSON.stringify(session));
    } else {
        localStorage.removeItem("auction_token");
        localStorage.removeItem("auction_session");
    }
}

function notify(message, type = "info") {
    els.toast.textContent = message;
    els.toast.className = `toast ${type}`.trim();
    setTimeout(() => els.toast.className = "toast hidden", 3000);
}

// --- Routing & Navigation ---

function switchView(viewId) {
    state.currentView = viewId;
    document.querySelectorAll('.view').forEach(v => v.classList.add('hidden'));
    const target = document.getElementById(viewId + 'View');
    if (target) target.classList.remove('hidden');

    document.querySelectorAll('.nav-link').forEach(link => {
        link.classList.toggle('active', link.dataset.view === viewId);
    });

    if (viewId === 'dashboard') renderDashboard();
    if (viewId === 'auctions') renderAuctions();
    if (viewId === 'my-bids') renderMyBids();
    if (viewId === 'admin') renderAdminDashboard();
}

function renderNav() {
    const loggedIn = Boolean(state.session);
    els.guestNav.classList.toggle("hidden", loggedIn);
    els.userNav.classList.toggle("hidden", !loggedIn);
    els.mainNav.classList.toggle("hidden", !loggedIn);

    if (loggedIn) {
        els.userName.textContent = state.session.username.split(' ')[0];
        els.userAvatar.textContent = state.session.username[0].toUpperCase();
        els.adminLink.classList.toggle("hidden", state.session.role !== "ADMIN");
    }
}

// --- Rendering Logic ---

function formatMoney(value) {
    return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 }).format(value).replace("INR", "Rs");
}

function getIcon(type) {
    const icons = { ELECTRONICS: '📱', ART: '🎨', VEHICLE: '🚗' };
    return icons[type.toUpperCase()] || '📦';
}

function createAuctionCard(auction, options = {}) {
    const isEnded = auction.state === 'FINISHED' || auction.state === 'CANCELED';
    const timeLabel = isEnded ? 'Ended' : relativeEndShort(auction.endTime);
    
    const imageHtml = auction.imageFilename ? 
        `<img src="/uploads/${auction.imageFilename}" style="width:100%; height:100%; object-fit:cover;">` : 
        getIcon(auction.itemType);

    return `
        <article class="auction-card" onclick="openDetail('${auction.id}')">
            <div class="image-placeholder">
                ${imageHtml}
                <div class="tag-top-left">${auction.itemType}</div>
                ${isEnded ? '<div class="tag-top-right">Ended</div>' : `<div class="tag-bottom-right">${timeLabel} left</div>`}
            </div>
            <div class="auction-card-content">
                <p class="subtle eyebrow">by ${auction.ownerUsername}</p>
                <h3>${auction.itemName}</h3>
                <p class="subtle">${auction.description}</p>
                <div class="auction-card-footer">
                    <div class="bid-info">
                        <p class="subtle eyebrow">CURRENT BID</p>
                        <span class="bid-amount">${formatMoney(auction.currentPrice)}</span>
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
    const sorted = [...state.auctions].sort((a, b) => b.startTime - a.startTime);
    els.latestGrid.innerHTML = sorted.slice(0, 4).map(a => createAuctionCard(a)).join("");
    
    els.statTotal.textContent = state.auctions.length;
    els.statActive.textContent = state.auctions.filter(a => a.state === 'RUNNING').length;
    els.statMine.textContent = state.auctions.filter(a => a.ownerId === state.session?.userId).length;
}

function renderAuctions() {
    const search = els.searchInput?.value.toLowerCase() || "";
    const category = els.categoryFilter?.value || "All";
    
    const filtered = state.auctions.filter(a => {
        const matchesSearch = !search || 
            a.itemName.toLowerCase().includes(search) || 
            a.description.toLowerCase().includes(search);
        
        const matchesCategory = category === "All" || 
            a.itemType.toUpperCase() === category.toUpperCase();
            
        return matchesSearch && matchesCategory;
    });

    if (els.fullGrid) {
        els.fullGrid.innerHTML = filtered.map(a => createAuctionCard(a)).join("");
    }
    const countEl = document.getElementById("auctionCount");
    if (countEl) {
        countEl.textContent = `${filtered.length} items found`;
    }
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

    els.myBidsGrid.innerHTML = filtered.map(a => createAuctionCard(a)).join("");
}

function renderAdminDashboard() {
    els.adminGrid.innerHTML = state.auctions.slice(0, 4).map(a => createAuctionCard(a)).join("");
    els.statAdminActive.textContent = state.auctions.filter(a => a.state === 'RUNNING').length;
    els.statAdminTotal.textContent = state.auctions.length;
    els.statAdminUsers.textContent = state.users.length || 259;
}

function openDetail(id) {
    state.selectedAuctionId = id;
    const auction = state.auctions.find(a => a.id === id);
    if (!auction) return;

    els.detailTitle.textContent = auction.itemName;
    els.detailDesc.textContent = auction.description;
    els.detailPrice.textContent = formatMoney(auction.currentPrice);
    els.detailStartPrice.textContent = `Started at ${formatMoney(auction.startingPrice)}`;
    els.detailBidCount.textContent = auction.bidCount;
    els.detailCategory.textContent = auction.itemType;
    els.sellerName.textContent = auction.ownerUsername;
    els.sellerAvatar.textContent = auction.ownerUsername[0].toUpperCase();
    
    const imageHtml = auction.imageFilename ? 
        `<img src="/uploads/${auction.imageFilename}" style="width:100%; height:100%; object-fit:cover; border-radius:20px;">` : 
        getIcon(auction.itemType);
    els.detailImage.innerHTML = `${imageHtml}<div class="state-overlay">${auction.state}</div>`;

    const isEnded = auction.state === 'FINISHED';
    els.timerSection.classList.toggle('hidden', isEnded);
    els.bidActions.classList.toggle('hidden', isEnded || auction.ownerId === state.session?.userId);
    els.endedMsg.classList.toggle('hidden', !isEnded);
    els.winnerSection.classList.toggle('hidden', !isEnded || !auction.highestBidderUsername);

    if (isEnded && auction.highestBidderUsername) {
        document.getElementById("winnerName").textContent = auction.highestBidderUsername;
        document.getElementById("winnerBid").textContent = formatMoney(auction.currentPrice);
    }

    els.bidHistoryList.innerHTML = auction.bidHistory.length ? 
        [...auction.bidHistory].reverse().map((bid, i) => `
            <div class="bid-item">
                <div class="bidder-meta">
                    <div class="avatar">${bid.bidderUsername[0]}</div>
                    <div>
                        <strong>${bid.bidderUsername}</strong> ${i === 0 ? '<span class="leading-badge">LEADING</span>' : ''}
                        <div class="subtle">${new Date(bid.timestamp).toLocaleString()}</div>
                    </div>
                </div>
                <strong>${formatMoney(bid.amount)}</strong>
            </div>
        `).join("") : '<p class="subtle">No bids yet.</p>';

    els.watcherList.innerHTML = Array.from({length: auction.watchingCount % 5 + 1}).map(() => `
        <div class="avatar" title="User">U</div>
    `).join("") + (auction.watchingCount > 0 ? `<span class="subtle">+${auction.watchingCount} more</span>` : '');

    switchView('detail');
    updateCountdown();
}

function updateCountdown() {
    if (state.currentView !== 'detail' || !state.selectedAuctionId) return;
    const auction = state.auctions.find(a => a.id === state.selectedAuctionId);
    if (!auction || auction.state === 'FINISHED') return;

    const delta = auction.endTime - Date.now();
    if (delta <= 0) {
        loadAuctions();
        return;
    }

    const d = Math.floor(delta / (1000 * 60 * 60 * 24));
    const h = Math.floor((delta % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));
    const m = Math.floor((delta % (1000 * 60 * 60)) / (1000 * 60));
    const s = Math.floor((delta % (1000 * 60)) / 1000);

    document.getElementById("days").textContent = d.toString().padStart(2, '0');
    document.getElementById("hours").textContent = h.toString().padStart(2, '0');
    document.getElementById("mins").textContent = m.toString().padStart(2, '0');
    document.getElementById("secs").textContent = s.toString().padStart(2, '0');
}

// --- Event Listeners ---

document.querySelectorAll('[data-view]').forEach(el => {
    el.addEventListener('click', e => {
        e.preventDefault();
        switchView(el.dataset.view);
    });
});

// Logo Home Navigation
document.getElementById("goHome").addEventListener('click', () => {
    if (!state.session) {
        switchView('landing');
    } else {
        switchView('dashboard');
    }
});

// Mobile menu toggle
document.getElementById("mobileMenuBtn")?.addEventListener('click', () => {
    notify("Mobile menu coming soon in the next update!", "info");
});

// Placeholder links
document.querySelectorAll('.nav-right #guestNav .nav-link').forEach(link => {
    link.addEventListener('click', (e) => {
        if (!link.id && !link.dataset.view) {
            e.preventDefault();
            notify(`${link.textContent} page coming soon!`, "info");
        }
    });
});

// Admin "All Users" placeholder
document.querySelector('#adminView .btn-primary')?.addEventListener('click', () => {
    const userCount = state.users.length || 259;
    notify(`Total registered users: ${userCount}`, "success");
});

document.getElementById("openLogin").addEventListener('click', () => openAuth('login'));
document.getElementById("openRegister").addEventListener('click', () => openAuth('register'));
document.getElementById("heroSignIn").addEventListener('click', () => openAuth('login'));
document.getElementById("heroStart").addEventListener('click', () => openAuth('register'));
document.getElementById("detailBack").addEventListener('click', () => switchView('auctions'));

function openAuth(mode) {
    const isReg = mode === 'register';
    els.authTitle.textContent = isReg ? 'Create Account' : 'Sign In';
    els.authSubmit.textContent = isReg ? 'Create Account' : 'Sign In';
    els.authToggleText.textContent = isReg ? 'Already have an account?' : "Don't have an account?";
    els.authToggleLink.textContent = isReg ? 'Sign in' : 'Sign up';
    els.roleGroup.classList.toggle('hidden', !isReg);
    switchView('auth');
}

els.authToggleLink.addEventListener('click', e => {
    e.preventDefault();
    openAuth(els.authTitle.textContent.includes('Sign In') ? 'register' : 'login');
});

els.authForm.addEventListener('submit', async e => {
    e.preventDefault();
    const formData = new FormData(e.target);
    const data = Object.fromEntries(formData.entries());
    const isReg = !els.roleGroup.classList.contains('hidden');
    
    try {
        const session = await api(isReg ? "/api/register" : "/api/login", {
            method: "POST",
            body: JSON.stringify(data)
        });
        saveSession(session);
        renderNav();
await loadAuctions();
switchView('dashboard');
window.location.reload();
        notify(isReg ? "Account created!" : "Welcome back!", "success");
    } catch (err) {
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
    const amount = Number(document.getElementById("bidInput").value);
    try {
        await api(`/api/auctions/${state.selectedAuctionId}/bid`, {
            method: "POST",
            body: JSON.stringify({ amount })
        });
        notify("Bid placed successfully!", "success");
        await loadAuctions();
        openDetail(state.selectedAuctionId);
    } catch (err) {
        notify(err.message, "danger");
    }
});

document.getElementById("autoBidBtn").addEventListener('click', async () => {
    const maxBid = Number(document.getElementById("autoMaxInput").value);
    const increment = Number(document.getElementById("autoIncInput").value);
    try {
        await api(`/api/auctions/${state.selectedAuctionId}/autobid`, {
            method: "POST",
            body: JSON.stringify({ maxBid, increment })
        });
        notify("Auto-bid configured", "success");
        await loadAuctions();
    } catch (err) {
        notify(err.message, "danger");
    }
});

document.getElementById("auctionForm").addEventListener('submit', async e => {
    e.preventDefault();
    const formData = new FormData(e.target);
    const body = Object.fromEntries(formData.entries());
    body.startingPrice = Number(body.startingPrice);
    body.durationMinutes = Number(body.durationMinutes);
    
    const fileInput = document.getElementById("imageInput");
    if (fileInput && fileInput.files.length > 0) {
        try {
            const file = fileInput.files[0];
            const buffer = await file.arrayBuffer();
            const uploadResult = await api("/api/upload", {
                method: "POST",
                body: buffer,
                headers: { "Content-Type": "application/octet-stream" }
            });
            body.imageFilename = uploadResult.filename;
        } catch (err) {
            notify("Image upload failed: " + err.message, "danger");
            return;
        }
    }
    
    try {
        await api("/api/auctions", { method: "POST", body: JSON.stringify(body) });
        notify("Auction created successfully!", "success");
        e.target.reset();
await loadAuctions();
switchView('dashboard');
window.location.reload();
    } catch (err) {
        notify(err.message, "danger");
    }
});

document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => {
        document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        state.activeTab = tab.dataset.filter;
        renderMyBids();
    });
});

els.searchInput?.addEventListener('input', () => {
    if (state.currentView === 'auctions') renderAuctions();
});
els.categoryFilter?.addEventListener('change', () => {
    if (state.currentView === 'auctions') renderAuctions();
});

// --- Real-time Updates (WebSocket) ---

function initWebSocket() {
    const ws = new WebSocket(`ws://${window.location.hostname}:8889`);

    ws.onopen = () => console.log("Connected to auction updates");
    
    ws.onmessage = (event) => {
        console.log("Update received:", event.data);
        state.lastEventTime = Date.now();
        loadAuctions();
        if (state.currentView === 'detail' && state.selectedAuctionId) {
            openDetail(state.selectedAuctionId);
        }
    };

    ws.onclose = () => {
        console.log("WebSocket disconnected. Retrying in 5s...");
        setTimeout(initWebSocket, 5000);
    };

    ws.onerror = (err) => console.error("WebSocket error:", err);
}

// --- Initialization ---

async function loadAuctions() {
    state.auctions = await api("/api/auctions");
    if (state.session?.role === "ADMIN") {
        state.users = await api("/api/users");
    }
    if (state.currentView === 'dashboard') renderDashboard();
    if (state.currentView === 'auctions') renderAuctions();
    if (state.currentView === 'my-bids') renderMyBids();
    if (state.currentView === 'admin') renderAdminDashboard();
}

// Global scope for onclick
window.openDetail = openDetail;

async function init() {
    renderNav();
    if (state.session) {
await loadAuctions();
switchView('dashboard');
window.location.reload();
    } else {
        switchView('landing');
    }
    
    setInterval(updateCountdown, 1000);
    initWebSocket();
}

init();
