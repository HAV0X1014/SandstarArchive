const app = document.getElementById('app');
const authSection = document.getElementById('authSection');
const LIMIT = 24;
let isLoading = false;
const searchBar = document.getElementById('searchBar');
const searchResults = document.getElementById('searchResults');
const imageSearchInput = document.getElementById('imageSearchInput');
let searchTimeout;

// --- STATE MANAGEMENT & CACHING ---
let currentUrlKey = window.location.pathname + window.location.search;
let pageCache = {};
let accountCache = {};
let validRatings = { content: [], safety: [] };

let selectedFilters = {
    content: [],
    safety: [],
    sort: 'newest'
};

function saveScrollState() {
    if (!pageCache[currentUrlKey]) pageCache[currentUrlKey] = {};
    pageCache[currentUrlKey].scrollY = window.scrollY;
}

// --- AUTH LOGIC (RBAC) ---
let currentUser = {
    loggedIn: false,
    username: null,
    role: 'Read'
};

function canWrite() {
    return currentUser.role === 'Write' || currentUser.role === 'Execute';
}

function canExecute() {
    return currentUser.role === 'Execute';
}

function renderAuth() {
    if (currentUser.loggedIn) {
        let adminBtn = canExecute() ? `<button class="btn btn-primary" onclick="navigateTo('/admin')">Admin</button>` : '';
        authSection.innerHTML = `
            ${adminBtn}
            <a href="javascript:void(0)" onclick="navigateTo('/me')" style="margin: 0 10px; font-size: 0.85rem; color: var(--text-secondary); text-decoration: none; font-weight: bold;">${currentUser.username}</a>
            <button class="btn" onclick="logout()" style="background: #a31; color: white; border-color: #611;">Logout</button>
        `;
    } else {
        authSection.innerHTML = `<button class="btn btn-primary" onclick="openAuthModal('login')">Log In / Sign Up</button>`;
    }
}

// --- MODAL LOGIC ---
function openAuthModal(mode) {
    document.getElementById('authModal').classList.remove('hidden');
    toggleAuthMode(mode);
}

function closeAuthModal() {
    document.getElementById('authModal').classList.add('hidden');
}

function toggleAuthMode(mode) {
    if (mode === 'login') {
        document.getElementById('loginFormContainer').classList.remove('hidden');
        document.getElementById('registerFormContainer').classList.add('hidden');
    } else {
        document.getElementById('loginFormContainer').classList.add('hidden');
        document.getElementById('registerFormContainer').classList.remove('hidden');
    }
}

async function submitLogin() {
    const identifier = document.getElementById('loginIdentifier').value;
    const password = document.getElementById('loginPassword').value;

    if (!identifier || !password) return alert("Please fill in all fields.");

    const res = await fetch('/api/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ identifier, password })
    });

    const data = await res.json();
    if (data.success) {
        currentUser = { loggedIn: true, username: data.username, role: data.role };
        closeAuthModal();
        location.reload(); // Reload to refresh permissions across the UI
    } else {
        alert(data.error || "Login failed");
    }
}

async function submitRegister() {
    const username = document.getElementById('regUsername').value;
    const email = document.getElementById('regEmail').value;
    const password = document.getElementById('regPassword').value;
    const inviteKey = document.getElementById('regInvite').value;

    // UPDATE: Now checks for inviteKey!
    if (!username || !password || password.length < 6 || !inviteKey) {
        return alert("Username, Password (min 6 chars), and Invite Key are required.");
    }

    const payload = { username, password, inviteKey };
    if (email) payload.email = email;

    const res = await fetch('/api/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    });

    const data = await res.json();
    if (data.success) {
        alert("Registration successful! You can now log in.");
        toggleAuthMode('login');
        document.getElementById('loginIdentifier').value = username;
        document.getElementById('loginPassword').value = '';
    } else {
        alert(data.error || "Registration failed");
    }
}

async function logout() {
    await fetch('/api/logout', { method: 'POST' });
    currentUser = { loggedIn: false, username: null, role: 'Read' };
    navigateTo('/');
    location.reload();
}

// --- ROUTER ---
function router() {
    const path = window.location.pathname;
    const urlParams = new URLSearchParams(window.location.search);
    const page = parseInt(urlParams.get('page')) || 1;

    app.innerHTML = '';

    if (path === '/' || path === '/index.html') {
        renderGlobalFeed(page);
    } else if (path === '/artists') {
        renderAllArtistsView();
        window.scrollTo(0, 0);
    } else if (path === '/admin') {
        renderAdminView();
        window.scrollTo(0, 0);
    } else if (path === '/admin/keys') {
        renderAdminKeysView(page);
        window.scrollTo(0, 0);
    } else if (path === '/admin/users') {
        renderAdminUsersView(page);
        window.scrollTo(0, 0);
    } else if (path === '/me') {
        renderProfileView();
        window.scrollTo(0, 0);
    } else if (path.startsWith('/artist/')) {
        renderArtistView(path.split('/')[2]);
        window.scrollTo(0, 0);
    } else if (path.startsWith('/account/')) {
        renderAccountView(path.split('/')[2], page);
    } else if (path.startsWith('/post/')) {
        renderPostDetail(path.split('/')[2]);
        window.scrollTo(0, 0);
    } else if (path.startsWith('/media/')) {
        renderMediaDetail(path.split('/')[2]);
        window.scrollTo(0, 0);

    } else {
        renderGlobalFeed(page);
    }
}

// --- VIEWS ---

async function renderGlobalFeed(page) {
    app.innerHTML = `
        <div class="page-container">
            <h1>Global Feed</h1>
        </div>
        <div id="gallery" class="gallery"></div>
        <div id="pagination" class="pagination"></div>
    `;
    loadPage(null, page);
}

function renderAdminView() {
    if (!canExecute()) {
        navigateTo('/');
        return;
    }

    const cards = [
        {
            task: "add account new create",
            title: "Add Account",
            html: `
                <input type="text" id="addAccHandle" class="input-textarea admin-input" placeholder="Twitter Handle (no @)">
                <input type="text" id="addAccArtist" class="input-textarea admin-input" placeholder="Artist Name">
                <label class="admin-label"><input type="checkbox" id="addAccDownload" checked> Enable Downloads</label>
                <select id="addAccSafety" class="admin-input"><option value="" disabled selected>Safety Rating</option>${generateOptionsHtml('Safety', 'Safe')}</select>
                <button class="btn btn-primary" onclick="adminRequest('POST', '/api/accounts', { handle: document.getElementById('addAccHandle').value, artist: document.getElementById('addAccArtist').value, download: document.getElementById('addAccDownload').checked, safety: document.getElementById('addAccSafety').value })">Submit</button>
            `
        },
        {
            task: "edit update modify account",
            title: "Edit Account",
            html: `
                <input type="text" id="editAccHandle" class="input-textarea admin-input" placeholder="Target Twitter Handle (Req)">
                <input type="text" id="editAccDisplay" class="input-textarea admin-input" placeholder="New Display Name">
                <select id="editAccStatus" class="admin-input"><option value="">-- No Change (Account Status) --</option><option value="Active">Active</option><option value="Deleted">Deleted</option><option value="Suspended">Suspended</option></select>
                <select id="editAccProtected" class="admin-input"><option value="">-- No Change (Protected) --</option><option value="true">True</option><option value="false">False</option></select>
                <select id="editAccDownload" class="admin-input"><option value="">-- No Change (Download) --</option><option value="true">True</option><option value="false">False</option></select>
                <select id="editAccSafety" class="admin-input"><option value="">-- No Change (Safety) --</option>${generateOptionsHtml('Safety', '')}</select>
                <button class="btn btn-primary" onclick="submitEditAccount()">Submit</button>
            `
        },
        {
            task: "delete remove account",
            title: "Delete Account",
            html: `
                <input type="text" id="delAccHandle" class="input-textarea admin-input" placeholder="Twitter Handle">
                <button class="btn btn-primary" onclick="adminRequest('DELETE', '/api/accounts/' + document.getElementById('delAccHandle').value, null)">Submit</button>
            `
        },
        {
            task: "add new alias name",
            title: "Add Alias",
            html: `
                <input type="text" id="aliasArtist" class="input-textarea admin-input" placeholder="Existing Artist Name">
                <input type="text" id="aliasName" class="input-textarea admin-input" placeholder="New Alias Name">
                <select id="aliasSafety" class="admin-input">${generateOptionsHtml('Safety', 'Safe')}</select>
                <button class="btn btn-primary" onclick="adminRequest('POST', '/api/artists/' + document.getElementById('aliasArtist').value + '/aliases', { aliasName: document.getElementById('aliasName').value, safetyRating: document.getElementById('aliasSafety').value })">Submit</button>
            `
        },
        {
            task: "edit update modify artist description text",
            title: "Edit Artist Description",
            html: `
                <input type="text" id="descArtist" class="input-textarea admin-input" placeholder="Artist Name">
                <textarea id="descText" class="input-textarea admin-input" rows="3" placeholder="New Description"></textarea>
                <button class="btn btn-primary" onclick="adminRequest('PATCH', '/api/artists/' + document.getElementById('descArtist').value, { description: document.getElementById('descText').value })">Submit</button>
            `
        },
        {
            task: "scrape fetch update download from post",
            title: "Scrape From Post",
            html: `
                <input type="text" id="scrapePostId" class="input-textarea admin-input" placeholder="Post ID (e.g. 1876543210)">
                <button class="btn btn-primary" onclick="adminRequest('POST', '/api/tasks/scrape', { postId: document.getElementById('scrapePostId').value })">Submit</button>
            `
        },
        {
            task: "download fetch specific post url",
            title: "Download Post",
            html: `
                <input type="text" id="dlPostUrl" class="input-textarea admin-input" placeholder="Post URL">
                <select id="dlPostContent" class="admin-input"><option value="" disabled selected>Content Rating</option>${generateOptionsHtml('Content', '')}</select>
                <select id="dlPostSafety" class="admin-input"><option value="" disabled selected>Safety Rating</option>${generateOptionsHtml('Safety', '')}</select>
                <button class="btn btn-primary" onclick="adminRequest('POST', '/api/tasks/download', { url: document.getElementById('dlPostUrl').value, contentRating: document.getElementById('dlPostContent').value, safetyRating: document.getElementById('dlPostSafety').value })">Submit</button>
            `
        },
        {
            task: "bot api token generate revoke view user",
            title: "Manage Bot Tokens",
            html: `
                <input type="number" id="botTokenUserId" class="input-textarea admin-input" placeholder="User ID">
                <div style="display: flex; gap: 5px; margin-top: 5px;">
                    <button class="btn btn-primary" style="flex:1;" onclick="adminManageBotToken('GET')">View</button>
                    <button class="btn btn-primary" style="flex:1;" onclick="adminManageBotToken('POST')">Generate</button>
                    <button class="btn" style="flex:1; background: #a22; border-color: #711;" onclick="adminManageBotToken('DELETE')">Revoke</button>
                </div>
            `
        }
    ];

    app.innerHTML = `
        <div class="page-container admin-page">
            <div style="display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 15px;">
                <h1>Admin Control Panel</h1>
                <button class="btn btn-primary" onclick="navigateTo('/admin/keys')">Manage Invite Keys &rarr;</button>
                <button class="btn btn-primary" onclick="navigateTo('/admin/users')">Manage Users &rarr;</button>
            </div>
            <input type="text" id="adminTaskFilter" class="input-textarea" style="margin: 20px 0; max-width: 400px;" placeholder="Filter admin tasks by name...">
            <div class="admin-tasks" id="adminTasksContainer">
                ${cards.map(c => `
                    <div class="admin-card" data-task="${c.task}">
                        <h3>${c.title}</h3>
                        ${c.html}
                    </div>
                `).join('')}
            </div>
        </div>
    `;

    document.getElementById('adminTaskFilter').addEventListener('input', (e) => {
        const term = e.target.value.toLowerCase();
        document.querySelectorAll('.admin-card').forEach(card => {
            if (card.dataset.task.includes(term)) card.classList.remove('hidden');
            else card.classList.add('hidden');
        });
    });
}

async function renderAdminKeysView(page) {
    if (!canExecute()) {
        navigateTo('/');
        return;
    }

    app.innerHTML = `
        <div class="page-container admin-page" style="max-width: 100%;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
                <h1>Manage Invite Keys</h1>
                <button class="btn" onclick="navigateTo('/admin')">&larr; Back to Admin</button>
            </div>

            <!-- CREATE NEW KEY -->
            <div class="new-key-card" style="margin-bottom: 30px; display: flex; gap: 10px; align-items: center; flex-wrap: wrap;">
                <h3 style="margin: 0; border: none; padding: 0;">Create New Key:</h3>
                <small>Grant role:</small>
                <select id="newKeyRole" class="admin-input" style="width: auto; margin: 0;">
                    <option value="Read">Read Role</option>
                    <option value="Write">Write Role</option>
                    <option value="Execute">Execute Role</option>
                </select>
                <small>Max Uses (-1 for unlimited):</small>
                <input type="number" id="newKeyUses" class="input-textarea admin-input" style="width: 120px; margin: 0;" placeholder="Uses (-1 = Inf)" value="-1">

                <div style="display: flex; align-items: center; gap: 5px;">
                    <small>Expires:</small>
                    <input type="datetime-local" id="newKeyExpires" class="admin-input" style="margin: 0;" title="Leave blank for never">
                </div>

                <button class="btn btn-primary" onclick="createNewKey()">Generate</button>
            </div>

            <!-- KEYS TABLE -->
            <div style="overflow-x: auto;">
                <table class="admin-table">
                    <thead>
                        <tr>
                            <th>Key</th>
                            <th>Role</th>
                            <th>Uses (Current / Max)</th>
                            <th>Expires At (Local Time)</th>
                            <th>Creator ID</th>
                            <th>Created At</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody id="keysTableBody">
                        <tr><td colspan="7">Loading keys...</td></tr>
                    </tbody>
                </table>
            </div>
            <div id="keysPagination" class="pagination"></div>
        </div>
    `;

    fetchAndRenderKeys(page);
}

async function fetchAndRenderKeys(page) {
    try {
        const res = await fetch(`/api/keys?page=${page}`);
        const keys = await res.json();
        const tbody = document.getElementById('keysTableBody');

        if (keys.length === 0) {
            tbody.innerHTML = `<tr><td colspan="7" style="text-align: center;">No keys found on this page.</td></tr>`;
        } else {
            tbody.innerHTML = keys.map(k => {
                const isUnlimited = k.maxUses === -1;
                const usageText = isUnlimited ? `${k.timesUsed} / &infin;` : `${k.timesUsed} /`;

                // Format Expiration Date for the HTML input (YYYY-MM-DDThh:mm)
                let expDate = '';
                if (k.expiresAt !== -1) {
                    // Adjust to local timezone format for datetime-local input
                    const d = new Date(k.expiresAt * 1000);
                    d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
                    expDate = d.toISOString().slice(0, 16);
                }

                return `
                <tr>
                    <td style="font-family: monospace; font-size: 1.1rem; color: var(--primary);">${k.inviteKey}</td>
                    <td>
                        <select id="role-${k.id}" class="admin-input" style="margin: 0; min-width: 90px;">
                            <option value="Read" ${k.grantRole === 'Read' ? 'selected' : ''}>Read</option>
                            <option value="Write" ${k.grantRole === 'Write' ? 'selected' : ''}>Write</option>
                            <option value="Execute" ${k.grantRole === 'Execute' ? 'selected' : ''}>Execute</option>
                        </select>
                    </td>
                    <td style="display: flex; align-items: center; gap: 8px; border-bottom: none;">
                        <span style="white-space: nowrap;">${usageText}</span>
                        <input type="number" id="uses-${k.id}" class="admin-input input-textarea" style="width: 70px; margin: 0;" value="${k.maxUses}">
                    </td>
                    <td>
                        <input type="datetime-local" id="expires-${k.id}" class="admin-input" style="margin: 0;" value="${expDate}" title="Clear to make permanent">
                    </td>
                    <td style="color: #aaa;">${k.createdByUserId === 0 ? 'System' : k.createdByUserId}</td>
                    <td style="color: #aaa; font-size: 0.85rem;">${new Date(k.creationDate * 1000).toLocaleString()}</td>
                    <td>
                        <button class="btn" style="background: #2a2; margin-bottom: 4px; width: 100%;" onclick="saveKey(${k.id})">Save</button>
                        <button class="btn" style="background: #a22; width: 100%;" onclick="deleteKey(${k.id})">Delete</button>
                    </td>
                </tr>
            `}).join('');
        }

        const paginationDiv = document.getElementById('keysPagination');
        let prevDisabled = page <= 1 ? 'disabled' : '';
        let nextDisabled = keys.length < 10 ? 'disabled' : '';

        paginationDiv.innerHTML = `
            <button class="page-btn" ${prevDisabled} onclick="navigateTo('/admin/keys?page=${page - 1}')">Previous</button>
            <span class="pagination-text">Page ${page}</span>
            <button class="page-btn" ${nextDisabled} onclick="navigateTo('/admin/keys?page=${page + 1}')">Next</button>
        `;

    } catch (e) {
        alert("Failed to fetch keys.");
        console.error(e);
    }
}

async function createNewKey() {
    const role = document.getElementById('newKeyRole').value;
    const maxUses = document.getElementById('newKeyUses').value;
    const expiresInput = document.getElementById('newKeyExpires').value;

    // Convert local datetime to Unix Epoch Seconds, or send -1 if empty
    const expiresAt = expiresInput ? Math.floor(new Date(expiresInput).getTime() / 1000) : -1;

    const res = await fetch('/api/keys', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ role, maxUses, expiresAt })
    });

    const data = await res.json();
    if (data.success) {
        navigateTo('/admin/keys?page=1');
    } else {
        alert(data.error || "Failed to create key");
    }
}

async function saveKey(id) {
    const role = document.getElementById(`role-${id}`).value;
    const maxUses = document.getElementById(`uses-${id}`).value;
    const expiresInput = document.getElementById(`expires-${id}`).value;

    const expiresAt = expiresInput ? Math.floor(new Date(expiresInput).getTime() / 1000) : -1;

    const res = await fetch(`/api/keys/${id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ role, maxUses, expiresAt })
    });

    if (res.ok) {
        const page = parseInt(new URLSearchParams(window.location.search).get('page')) || 1;
        fetchAndRenderKeys(page);
    } else {
        alert("Failed to update key.");
    }
}

async function deleteKey(id) {
    if (!confirm("Are you sure you want to permanently delete this key?")) return;

    const res = await fetch(`/api/keys/${id}`, { method: 'DELETE' });
    if (res.ok) {
        const page = parseInt(new URLSearchParams(window.location.search).get('page')) || 1;
        fetchAndRenderKeys(page);
    } else {
        alert("Failed to delete key.");
    }
}

// ==========================================
// USER MANAGEMENT VIEW
// ==========================================

async function renderAdminUsersView(page) {
    if (!canExecute()) {
        navigateTo('/');
        return;
    }

    app.innerHTML = `
        <div class="page-container admin-page" style="max-width: 100%;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
                <h1>Manage Users</h1>
                <button class="btn" onclick="navigateTo('/admin')">&larr; Back to Admin</button>
            </div>

            <div style="overflow-x: auto;">
                <table class="admin-table">
                    <thead>
                        <tr>
                            <th>ID / Username</th>
                            <th>Role</th>
                            <th>Banned</th>
                            <th>Admin Note</th>
                            <th>Joined (Key Used)</th>
                            <th>Actions</th>
                        </tr>
                    </thead>
                    <tbody id="usersTableBody">
                        <tr><td colspan="6">Loading users...</td></tr>
                    </tbody>
                </table>
            </div>
            <div id="usersPagination" class="pagination"></div>
        </div>
    `;

    fetchAndRenderUsers(page);
}

async function fetchAndRenderUsers(page) {
    try {
        const res = await fetch(`/api/users?page=${page}`);
        const users = await res.json();
        const tbody = document.getElementById('usersTableBody');

        if (users.length === 0) {
            tbody.innerHTML = `<tr><td colspan="6" style="text-align: center;">No users found.</td></tr>`;
        } else {
            tbody.innerHTML = users.map(u => {
                const joinedDate = new Date(u.creationDate).toLocaleDateString();
                const keyUsed = u.inviteKeyUsed ? `<br><small style="color:var(--text-muted)">Key: ${u.inviteKeyUsed}</small>` : '';

                return `
                <tr>
                    <td>
                        <strong class="text-primary">${u.username}</strong><br>
                        <small style="color: #888;">ID: ${u.id}</small>
                    </td>
                    <td>
                        <select id="u-role-${u.id}" class="admin-input" style="margin: 0;">
                            <option value="Read" ${u.role === 'Read' ? 'selected' : ''}>Read</option>
                            <option value="Write" ${u.role === 'Write' ? 'selected' : ''}>Write</option>
                            <option value="Execute" ${u.role === 'Execute' ? 'selected' : ''}>Execute</option>
                        </select>
                    </td>
                    <td>
                        <select id="u-banned-${u.id}" class="admin-input" style="margin: 0; background: ${u.banned ? '#421111' : '#111'};">
                            <option value="false" ${!u.banned ? 'selected' : ''}>False</option>
                            <option value="true" ${u.banned ? 'selected' : ''}>TRUE</option>
                        </select>
                    </td>
                    <td>
                        <input type="text" id="u-note-${u.id}" class="admin-input input-textarea" style="margin: 0; width: 100%; min-width: 150px;" value="${u.note || ''}" placeholder="Internal note...">
                    </td>
                    <td style="font-size: 0.85rem;">
                        ${joinedDate}
                        ${keyUsed}
                    </td>
                    <td>
                        <button class="btn" style="background: #2a2; width: 100%;" onclick="saveUser(${u.id})">Save</button>
                    </td>
                </tr>
            `}).join('');
        }

        const paginationDiv = document.getElementById('usersPagination');
        let prevDisabled = page <= 1 ? 'disabled' : '';
        let nextDisabled = users.length < 10 ? 'disabled' : '';

        paginationDiv.innerHTML = `
            <button class="page-btn" ${prevDisabled} onclick="navigateTo('/admin/users?page=${page - 1}')">Previous</button>
            <span class="pagination-text">Page ${page}</span>
            <button class="page-btn" ${nextDisabled} onclick="navigateTo('/admin/users?page=${page + 1}')">Next</button>
        `;

    } catch (e) {
        alert("Failed to fetch users.");
        console.error(e);
    }
}

async function saveUser(id) {
    const role = document.getElementById(`u-role-${id}`).value;
    const bannedStr = document.getElementById(`u-banned-${id}`).value;
    const note = document.getElementById(`u-note-${id}`).value;

    const banned = (bannedStr === "true");

    // Prevent accidental self-demotion
    if (currentUser.username && id === currentUser.id && role !== 'Execute') {
        if (!confirm("WARNING: You are about to remove your own Execute permissions! Are you sure?")) {
            return;
        }
    }

    const res = await fetch(`/api/users/${id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ role, banned, note })
    });

    if (res.ok) {
        // Subtle visual confirmation without a blocking alert
        const btn = event.target;
        const originalText = btn.innerText;
        btn.innerText = "Saved!";
        btn.style.background = "#181";
        setTimeout(() => {
            btn.innerText = originalText;
            btn.style.background = "#2a2";

            // Re-fetch to update background colors (like banned red tint)
            const page = parseInt(new URLSearchParams(window.location.search).get('page')) || 1;
            fetchAndRenderUsers(page);
        }, 1000);
    } else {
        alert("Failed to update user.");
    }
}

function submitEditAccount() {
    const handle = document.getElementById('editAccHandle').value;
    if (!handle) return alert("Target handle required.");

    const body = {};
    const dName = document.getElementById('editAccDisplay').value;
    const status = document.getElementById('editAccStatus').value;
    const prot = document.getElementById('editAccProtected').value;
    const dl = document.getElementById('editAccDownload').value;
    const safe = document.getElementById('editAccSafety').value;

    if (dName) body.displayName = dName;
    if (status) body.accountStatus = status;
    if (prot !== "") body.isProtected = (prot === "true");
    if (dl !== "") body.downloadStatus = (dl === "true");
    if (safe) body.safetyRating = safe;

    adminRequest('PATCH', `/api/accounts/${handle}`, body);
}

async function adminManageBotToken(action) {
    const userId = document.getElementById('botTokenUserId').value;
    if (!userId) {
        return alert("Please enter a User ID.");
    }

    if (action === 'POST' && !confirm(`Generate a new bot token for User ID ${userId}? Any existing token will be overwritten.`)) return;
    if (action === 'DELETE' && !confirm(`Revoke the bot token for User ID ${userId}?`)) return;

    try {
        const res = await fetch(`/api/users/${userId}/token`, {
            method: action,
            headers: { 'Content-Type': 'application/json' }
        });

        const data = await res.json();

        if (data.success) {
            if (action === 'GET') {
                prompt(`Active Bot Token for User ${userId}:`, data.token);
            } else if (action === 'POST') {
                prompt(`New Bot Token generated for User ${userId}. Copy it now:`, data.token);
            } else if (action === 'DELETE') {
                alert(`Bot token revoked for User ${userId}.`);
            }
        } else {
            alert(data.error || data.message || "Action failed.");
        }
    } catch (e) {
        alert("Request failed: " + e);
    }
}

async function renderProfileView() {
    if (!currentUser.loggedIn) {
        navigateTo('/');
        return;
    }

    app.innerHTML = `
        <div class="page-container" style="max-width: 600px;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
                <h1>My Account</h1>
                <span style="color: var(--text-muted);">Role: <strong style="color: var(--accent);">${currentUser.role}</strong></span>
            </div>

            <div class="admin-card" style="margin-bottom: 25px;">
                <h3 style="margin-top: 0;">Profile Details</h3>
                <div class="form-group">
                    <label>Username</label>
                    <input type="text" id="meUsername" class="form-input" placeholder="Display name and login name">
                </div>
                <div class="form-group">
                    <label>Email (Optional)</label>
                    <input type="email" id="meEmail" class="form-input" placeholder="Set this in case you need to be contacted">
                </div>
                <div class="form-group">
                    <label>New Password</label>
                    <input type="password" id="mePassword" class="form-input" placeholder="Leave blank to keep current password">
                </div>
                <div class="form-group">
                    <label>About Me</label>
                    <textarea id="meAbout" class="form-input" rows="4" placeholder="Info about you (external sites, names, etc)"></textarea>
                </div>

                <div style="display: flex; gap: 10px; margin-top: 20px;">
                    <button class="btn btn-primary" style="flex: 1;" onclick="saveProfile()">Save Changes</button>
                    <button class="btn" style="background: #a22; border-color: #711;" onclick="deleteAccount()">Delete Account</button>
                </div>
            </div>
        </div>
    `;

    // Fetch current profile details
    try {
        const res = await fetch('/api/me');
        if (res.ok) {
            const data = await res.json();
            document.getElementById('meUsername').value = data.username || '';
            document.getElementById('meEmail').value = data.email || '';
            document.getElementById('meAbout').value = data.aboutMe || '';
        }
    } catch (e) {
        console.error("Failed to fetch profile details", e);
    }
}

async function saveProfile() {
    const username = document.getElementById('meUsername').value;
    const email = document.getElementById('meEmail').value;
    const password = document.getElementById('mePassword').value;
    const aboutMe = document.getElementById('meAbout').value;

    const payload = { username, email, aboutMe };
    if (password) payload.password = password;

    const res = await fetch('/api/me', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    });

    const data = await res.json();
    if (data.success) {
        alert("Profile updated successfully!");
        currentUser.username = data.username;
        renderAuth(); // Update the top bar username visually
        document.getElementById('mePassword').value = ''; // clear password field
    } else {
        alert(data.error || "Failed to update profile.");
    }
}

async function deleteAccount() {
    const confirm1 = confirm("Are you completely sure you want to delete your account? This action cannot be undone.");
    if (!confirm1) return;

    const confirm2 = prompt("Type 'DELETE' to confirm account termination:");
    if (confirm2 !== 'DELETE') {
        alert("Account deletion cancelled.");
        return;
    }

    const res = await fetch('/api/me', { method: 'DELETE' });
    if (res.ok) {
        alert("Account deleted.");
        currentUser = { loggedIn: false, username: null, role: 'Read' };
        navigateTo('/');
        location.reload();
    } else {
        alert("Failed to delete account.");
    }
}

// Uses cookies automatically, no Auth Header needed!
async function adminRequest(method, url, body) {
    try {
        const options = {
            method: method,
            headers: {}
        };

        if (body) {
            options.headers['Content-Type'] = 'application/json';
            options.body = JSON.stringify(body);
        }

        const res = await fetch(url, options);
        const data = await res.json();

        if (data.success) {
            alert("Success: " + (data.message || JSON.stringify(data)));
        } else {
            alert("Error: " + (data.error || "Unknown error occurred"));
        }
    } catch (e) {
        alert("Request Failed: " + e);
    }
}

async function renderAllArtistsView() {
    app.innerHTML = `
        <div class="page-container">
            <h1>All Artists</h1>
            <div id="artistsList" class="gallery" style="margin-top: 20px;">
                <p>Loading artists...</p>
            </div>
        </div>`;

    try {
        const res = await fetch('/api/artists');
        const artists = await res.json();

        const container = document.getElementById('artistsList');
        if (artists.length === 0) {
            container.innerHTML = '<p>No artists found.</p>';
            return;
        }

        container.innerHTML = artists.map(a => {
            const url = `/artist/${encodeURIComponent(a.name)}`;
            return `
            <a href="${url}" class="post-card" onclick="if(event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey) { event.preventDefault(); navigateTo('${url}'); }" style="padding: 15px; display: block; height: auto; text-decoration: none; color: inherit;">
                <h3 class="text-primary">${a.name}</h3>
                <p style="font-size: 0.85rem; color: var(--text-muted); margin-top: 5px;">${a.description || 'No description provided.'}</p>
            </a>
        `}).join('');
    } catch (e) {
        document.getElementById('artistsList').innerHTML = '<p>Error loading artists.</p>';
    }
}

async function renderArtistView(name) {
    const res = await fetch(`/api/artists/slug/${name}`);
    const data = await res.json();
    app.innerHTML = `
        <div class="page-container">
            <h1>Artist: ${data.name}</h1>
            <p>${data.description || ''}</p>
            <br>
            <h3>Aliases</h3>
            <ul>${data.aliases.map(a => `<li>${a.aliasName} (${a.safetyRating})</li>`).join('')}</ul>
            <br>
            <h3>Connected Accounts</h3>
            <div class="gallery">
                ${data.accounts.map(acc => `
                    <a href="/account/${acc.twitterId}" class="post-card" onclick="if(event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey) { event.preventDefault(); navigateTo('/account/${acc.twitterId}'); }" style="text-decoration: none; color: inherit;">
                        <div class="card-footer" style="height: 100%;">
                            <strong class="text-primary">@${acc.screenName}</strong><br>${acc.displayName}<br>
                            <small>Status: ${acc.accountStatus}</small><br>
                            <small>Safety Rating: ${acc.safetyRating}</small>
                        </div>
                    </a>
                `).join('')}
            </div>
        </div>`;
}

async function renderAccountView(twitterId, page) {
    if (!accountCache[twitterId]) {
        const res = await fetch(`/api/accounts/${twitterId}`);
        accountCache[twitterId] = await res.json();
    }
    const acc = accountCache[twitterId];

    app.innerHTML = `
        <div class="account-container">
            <aside class="sidebar" id="sidebar">
                <a href="javascript:void(0)" onclick="navigateToArtistById(${acc.artistId})" class="text-primary" style="display: inline-block; margin-bottom: 15px; font-weight: bold;">← View Artist</a>

                <a href="https://x.com/${acc.screenName}" target="_blank"><h2>@${acc.screenName}</h2></a>
                <p><strong>${acc.displayName}</strong></p>
                <br>
                <p>Safety Rating: ${acc.safetyRating}</p>
                <p>Status: ${acc.accountStatus}</p>
                <p>Protected: ${acc.isProtected}</p>
                <p>Download Status: ${acc.downloadStatus}</p>
                <br>
                <small class="card-date">Twitter ID: ${acc.twitterId}</small><br>
                <small class="card-date">Artist ID: ${acc.artistId}</small>
            </aside>
            <div class="main-content">
                <div style="padding: 15px 15px 0 15px;">
                    <button class="btn" onclick="document.getElementById('sidebar').classList.toggle('hidden')">Toggle Sidebar</button>
                </div>
                <div id="gallery" class="gallery"></div>
                <div id="pagination" class="pagination"></div>
            </div>
        </div>`;

    loadPage(twitterId, page);
}

async function renderPostDetail(postId) {
    const res = await fetch(`/api/posts/${postId}`);
    const post = await res.json();
    const columns = post.media.length > 1 ? 'repeat(2, auto)' : 'auto';

    app.innerHTML = `
    <div class="detail-view">
        <div class="detail-content" style="grid-template-columns: ${columns};">
            ${post.media.map(m => {
        const filename = m.localPath.split(/[\\/]/).pop();
        const src = `/images/${m.contentRating}/${m.safetyRating}/${filename}`;
        return `
                <div class="detail-media-wrapper">
                    ${m.mediaType.includes('mp4') ?
                        `<video src="${src}" controls></video>` :
                        `<img src="${src}" onclick="navigateTo('/media/${m.id}')">`
                    }
                </div>`;
    }).join('')}
        </div>

        <div class="detail-footer">
            <div class="detail-footer-inner">
                <div style="flex: 1; min-width: 0;">
                    <div style="margin-bottom: 8px;">
                        <a href="javascript:void(0)" onclick="navigateTo('/account/${post.twitterId}')" class="text-primary" style="font-size: 1.1rem; font-weight: bold;">← View Account</a>
                    </div>
                    <div class="detail-meta">
                        <span>Post ID: ${post.postId}</span><span>•</span>
                        <span>${new Date(post.postDate * 1000).toLocaleString()}</span>
                    </div>
                    <div class="detail-text">${post.postText || '&nbsp;'}</div>
                    <a href="https://x.com/i/status/${post.postId}" target="_blank" class="text-primary" style="font-size: 0.85rem;">Original Twitter Post ↗</a>
                </div>

                <div class="controls-col">
                    <small>Post Rating:</small>
                    ${renderRatingControls(post.postId, 'post', post.contentRating, post.safetyRating)}
                </div>
            </div>
        </div>
    </div>`;
}

async function renderMediaDetail(mediaId) {
    const res = await fetch(`/api/media/${mediaId}`);
    const m = await res.json();
    const filename = m.localPath.split(/[\\/]/).pop();
    const src = `/images/${m.contentRating}/${m.safetyRating}/${filename}`;

    let captionContent = !canWrite()
        ? `<div class="detail-text" style="text-align: center; color: #ccc;">${m.caption || ''}</div>`
        : `
            <div class="caption-editor">
                <textarea id="caption-input" class="input-textarea" rows="2" placeholder="Enter media caption...">${m.caption || ''}</textarea>
                <button class="btn btn-primary" onclick="updateCaption(${m.id})">Save Caption</button>
            </div>`;

    app.innerHTML = `
        <div class="detail-view">
            <div class="detail-content">
                <div class="detail-media-wrapper">
                    ${m.mediaType.includes('mp4') ? `<video src="${src}" controls></video>` : `<img src="${src}">`}
                </div>
            </div>

            <div class="detail-footer">
                <div class="detail-footer-inner">
                    <div style="width: 250px; min-width: 0;">
                        <div style="margin-bottom: 6px;">
                            <a href="javascript:void(0)" onclick="navigateTo('/post/${m.postId}')" class="text-primary" style="font-size: 1rem; font-weight: bold;">← View Post</a>
                        </div>
                        <div class="detail-meta">
                            <span>Media ID: ${m.id}</span><span>•</span><span>${m.mediaType}</span>
                        </div>
                    </div>

                    <div style="flex: 1; display: flex; justify-content: center;">
                        ${captionContent}
                    </div>

                    <div class="controls-col" style="width: 250px;">
                        <small>Media Rating:</small>
                        ${renderRatingControls(m.id, 'media', m.contentRating, m.safetyRating)}
                    </div>
                </div>
            </div>
        </div>`;
}

// --- PAGINATION & CACHED RENDERER ---

async function loadPage(twitterId, pageNum) {
    if (isLoading) return;
    const urlKey = window.location.pathname + window.location.search;

    if (pageCache[urlKey] && pageCache[urlKey].posts) {
        renderPosts(pageCache[urlKey].posts, pageNum, twitterId);
        window.scrollTo(0, pageCache[urlKey].scrollY || 0);
        return;
    }

    isLoading = true;
    const offset = (pageNum - 1) * LIMIT;

    const cParams = selectedFilters.content.map(c => `content=${encodeURIComponent(c)}`).join('&');
    const sParams = selectedFilters.safety.map(s => `safety=${encodeURIComponent(s)}`).join('&');
    const sortParam = `sort=${selectedFilters.sort}`;

    const filterQuery = [cParams, sParams, sortParam].filter(x => x !== '').join('&');

    const url = twitterId
        ? `/api/accounts/${twitterId}/posts?limit=${LIMIT}&offset=${offset}&${filterQuery}`
        : `/api/posts?limit=${LIMIT}&offset=${offset}&${filterQuery}`;

    const res = await fetch(url);
    const posts = await res.json();

    if (!pageCache[urlKey]) pageCache[urlKey] = {};
    pageCache[urlKey].posts = posts;
    pageCache[urlKey].scrollY = 0;

    renderPosts(posts, pageNum, twitterId);
    window.scrollTo(0, 0);
    isLoading = false;
}

function renderPosts(posts, pageNum, twitterId) {
    const gallery = document.getElementById('gallery');
    gallery.innerHTML = '';

    if (posts.length === 0) {
        gallery.innerHTML = '<p style="padding: 20px;">No more posts available.</p>';
    } else {
        posts.forEach(post => {
            const card = document.createElement('a');
            card.className = 'post-card';
            card.href = `/post/${post.postId}`;
            card.style.color = 'inherit';
            card.style.textDecoration = 'none';

            card.addEventListener('click', function(e) {
                if (e.target.closest('select')) {
                    e.preventDefault();
                    return;
                }
                if (e.button === 0 && !e.ctrlKey && !e.metaKey && !e.shiftKey) {
                    e.preventDefault();
                    navigateTo(`/post/${post.postId}`);
                }
            });

            let mediaGrid = '';
            for (let i = 0; i < post.media.length; i += 2) {
                const rowMedia = post.media.slice(i, i + 2);
                let rowHtml = '';

                rowMedia.forEach((m) => {
                    const localFilename = m.localPath.split(/[\\/]/).pop();
                    const fullSrc = `/images/${m.contentRating}/${m.safetyRating}/${localFilename}`;
                    const thumbSrc = `/api/media/${m.id}/thumbnail`;

                    rowHtml += `
                        <div class="card-media-item">
                            ${m.mediaType.includes('mp4') ?
                                `<video src="${fullSrc}" controls></video>` :
                                `<img src="${thumbSrc}" loading="lazy" />`
                            }
                            <div class="card-overlay">
                                ${canWrite() ? `
                                    <select onchange="updateRating('${m.id}', 'media', 'Content', this.value)" onclick="event.stopPropagation();">
                                        ${generateOptionsHtml('Content', m.contentRating)}
                                    </select>
                                    <select onchange="updateRating('${m.id}', 'media', 'Safety', this.value)" style="margin-left: 4px;" onclick="event.stopPropagation();">
                                        ${generateOptionsHtml('Safety', m.safetyRating)}
                                    </select>
                                ` : `${m.contentRating} / ${m.safetyRating}`}
                            </div>
                        </div>`;
                });
                mediaGrid += `<div class="card-media-row">${rowHtml}</div>`;
            }

            card.innerHTML = `
                <div class="card-media-grid">${mediaGrid}</div>
                <div class="card-footer">
                    <div class="card-header">
                        <div class="card-author">${post.screenName}</div>
                        <div class="card-date">${new Date(post.postDate * 1000).toLocaleDateString()}</div>
                    </div>
                    <div class="card-caption">${post.postText || ''}</div>
                    ${canWrite() ? `
                        <div class="card-rating">
                            <span>Post Rating:</span>
                            <select onchange="updateRating('${post.postId}', 'post', 'Content', this.value)" onclick="event.stopPropagation();">
                                ${generateOptionsHtml('Content', post.contentRating)}
                            </select>
                            <select onchange="updateRating('${post.postId}', 'post', 'Safety', this.value)" onclick="event.stopPropagation();">
                                ${generateOptionsHtml('Safety', post.safetyRating)}
                            </select>
                        </div>
                    ` : ''}
                </div>`;
            gallery.appendChild(card);
        });
    }

    const paginationDiv = document.getElementById('pagination');
    if (paginationDiv) {
        let prevDisabled = pageNum <= 1 ? 'disabled' : '';
        let nextDisabled = posts.length < LIMIT ? 'disabled' : '';
        let pathPrefix = twitterId ? `/account/${twitterId}` : '/';
        let prevPath = pathPrefix === '/' ? `/?page=${pageNum - 1}` : `${pathPrefix}?page=${pageNum - 1}`;
        let nextPath = pathPrefix === '/' ? `/?page=${pageNum + 1}` : `${pathPrefix}?page=${pageNum + 1}`;

        paginationDiv.innerHTML = `
            <button class="page-btn" ${prevDisabled} onclick="navigateTo('${prevPath}')">Previous Page</button>
            <span class="pagination-text">Page ${pageNum}</span>
            <button class="page-btn" ${nextDisabled} onclick="navigateTo('${nextPath}')">Next Page</button>
        `;
    }
}

// --- FILTERS & SORTING ---

function populateFilterMenu() {
    const filterDropdown = document.getElementById('filterDropdown');
    if (!filterDropdown) return;

    let html = `<div class="filter-group">
        <h4>Sort By</h4>
        <select id="sortSelect" style="width: 100%; background: #111; color: #fff; border: 1px solid #333; padding: 6px; border-radius: 4px;">
            <option value="newest" ${selectedFilters.sort === 'newest' ? 'selected' : ''}>Newest First</option>
            <option value="oldest" ${selectedFilters.sort === 'oldest' ? 'selected' : ''}>Oldest First</option>
            <option value="random" ${selectedFilters.sort === 'random' ? 'selected' : ''}>Randomize</option>
        </select>
    </div>
    <div class="filter-group">
        <h4>Content Ratings</h4>
        <small style="color:var(--text-muted); display:block; margin-bottom: 6px;">Leave all unchecked for ANY</small>
    `;

    validRatings.content.forEach(r => {
        const checked = selectedFilters.content.includes(r) ? 'checked' : '';
        html += `<label class="filter-label"><input type="checkbox" class="content-filter" value="${r}" ${checked}> ${r}</label>`;
    });

    html += `</div><div class="filter-group">
        <h4>Safety Ratings</h4>
        <small style="color:var(--text-muted); display:block; margin-bottom: 6px;">Leave all unchecked for ANY</small>
    `;

    validRatings.safety.forEach(r => {
        const checked = selectedFilters.safety.includes(r) ? 'checked' : '';
        html += `<label class="filter-label"><input type="checkbox" class="safety-filter" value="${r}" ${checked}> ${r}</label>`;
    });

    html += `</div>
    <button class="btn btn-primary" style="width: 100%;" onclick="applyFilters()">Apply Filters</button>`;

    filterDropdown.innerHTML = html;
}

function toggleFilters() {
    document.getElementById('filterDropdown').classList.toggle('hidden');
}

function applyFilters() {
    const sortSelect = document.getElementById('sortSelect');
    const cBoxes = document.querySelectorAll('.content-filter:checked');
    const sBoxes = document.querySelectorAll('.safety-filter:checked');

    selectedFilters.sort = sortSelect.value;
    selectedFilters.content = Array.from(cBoxes).map(cb => cb.value);
    selectedFilters.safety = Array.from(sBoxes).map(cb => cb.value);

    localStorage.setItem('sandstar_filters', JSON.stringify(selectedFilters));

    pageCache = {};
    toggleFilters();
    router();
}

// --- HELPERS ---

function generateOptionsHtml(ratingType, currentValue) {
    const options = ratingType === 'Content' ? validRatings.content : validRatings.safety;
    const selectableOptions = options.filter(opt => opt !== 'Waiting');
    if (currentValue === 'Waiting' && !selectableOptions.includes('Waiting')) {
        selectableOptions.unshift('Waiting');
    }
    return selectableOptions.map(opt => `<option value="${opt}" ${currentValue === opt ? 'selected' : ''}>${opt}</option>`).join('');
}

function renderRatingControls(id, type, currentContent, currentSafety) {
    if (!canWrite()) return `<div class="controls">Content: ${currentContent}&nbsp; Safety: ${currentSafety}</div>`;
    return `
        <div class="controls">
            Content: <select onchange="updateRating('${id}', '${type}', 'Content', this.value)">
                ${generateOptionsHtml('Content', currentContent)}
            </select>
            Safety: <select onchange="updateRating('${id}', '${type}', 'Safety', this.value)">
                ${generateOptionsHtml('Safety', currentSafety)}
            </select>
        </div>`;
}

async function updateCaption(mediaId) {
    const captionText = document.getElementById('caption-input').value;

    const res = await fetch(`/api/media/${mediaId}`, {
        method: 'PATCH',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({ caption: captionText })
    });

    const data = await res.json();
    if (data.success) {
        alert("Caption updated!");
    } else {
        alert("Failed to update caption: " + (data.error || "Are you logged in?"));
    }
}

async function updateRating(id, targetType, ratingType, value) {
    const endpoint = targetType === 'post' ? `/api/posts/${id}` : `/api/media/${id}`;

    const body = {};
    if (ratingType === 'Content') body.contentRating = value;
    if (ratingType === 'Safety') body.safetyRating = value;

    await fetch(endpoint, {
        method: 'PATCH',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(body)
    });

    Object.values(pageCache).forEach(pageData => {
        if (pageData.posts) {
            pageData.posts.forEach(p => {
                if (targetType === 'post' && p.postId == id) {
                    if (ratingType === 'Content') p.contentRating = value;
                    if (ratingType === 'Safety') p.safetyRating = value;
                }
                if (targetType === 'media') {
                    p.media.forEach(m => {
                        if (m.id == id) {
                            if (ratingType === 'Content') m.contentRating = value;
                            if (ratingType === 'Safety') m.safetyRating = value;
                        }
                    });
                }
            });
        }
    });
}

function navigateTo(url) {
    saveScrollState();
    window.history.pushState({}, "", url);
    currentUrlKey = window.location.pathname + window.location.search;
    router();
}

async function navigateToArtistById(artistId) {
    try {
        const res = await fetch(`/api/artists/${artistId}`);
        const data = await res.json();
        if (data && data.name) {
            navigateTo(`/artist/${encodeURIComponent(data.name)}`);
        } else {
            alert("Artist not found.");
        }
    } catch (e) {
        console.error("Failed to route to artist", e);
    }
}

// --- SEARCH ENGINE ---

searchBar.addEventListener('input', () => {
    clearTimeout(searchTimeout);
    const query = searchBar.value.trim();
    if (query.length < 2) {
        searchResults.classList.add('hidden');
        return;
    }
    searchTimeout = setTimeout(() => performSearch(query), 300);
});

async function performSearch(query) {
    try {
        const [artistRes, accountRes] = await Promise.all([
            fetch(`/api/artists?q=${encodeURIComponent(query)}`),
            fetch(`/api/accounts?q=${encodeURIComponent(query)}`)
        ]);
        renderSearchResults(await artistRes.json(), await accountRes.json());
    } catch (err) {
        console.error("Search failed", err);
    }
}

function renderSearchResults(artists, accounts) {
    searchResults.innerHTML = '';

    if (artists.length === 0 && accounts.length === 0) {
        const div = document.createElement('div');
        div.className = 'search-item';
        div.innerText = 'No results found';
        searchResults.appendChild(div);
        searchResults.classList.remove('hidden');
        return;
    }

    if (artists.length > 0) {
        const header = document.createElement('div');
        header.className = 'search-header';
        header.innerText = 'Artists';
        searchResults.appendChild(header);

        artists.forEach(artist => {
            const div = document.createElement('div');
            div.className = 'search-item';
            div.innerText = artist.name;
            div.onclick = () => { navigateTo(`/artist/${encodeURIComponent(artist.name)}`); closeSearch(); };
            searchResults.appendChild(div);
        });
    }

    if (accounts.length > 0) {
        const header = document.createElement('div');
        header.className = 'search-header';
        header.innerText = 'Accounts';
        searchResults.appendChild(header);

        accounts.forEach(acc => {
            const div = document.createElement('div');
            div.className = 'search-item';
            div.innerHTML = `<div>@${acc.screenName}</div><small>${acc.displayName}</small>`;
            div.onclick = () => { navigateTo(`/account/${acc.twitterId}`); closeSearch(); };
            searchResults.appendChild(div);
        });
    }

    searchResults.classList.remove('hidden');
}

function closeSearch() {
    searchResults.classList.add('hidden');
    searchBar.value = '';
}

imageSearchInput.addEventListener('change', async (e) => {
    const file = e.target.files[0];
    if (!file) return;

    // Ask user for strictness or use a default
    const threshold = prompt("Enter similarity threshold (0-10). 0 is exact, 10 is loose.", "5") || "5";

    const formData = new FormData();
    formData.append('image', file);

    app.innerHTML = '<div class="page-container"><h1>Search Results</h1><div class="gallery" id="gallery"></div></div>';

    try {
        const res = await fetch(`/api/search/image?threshold=${threshold}`, {
            method: 'POST',
            body: formData
        });
        const matches = await res.json();

        renderImageSearchResults(matches);
    } catch (err) {
        alert("Search failed: " + err);
    }

    // Reset input
    imageSearchInput.value = '';
});

function renderImageSearchResults(matches) {
    const gallery = document.getElementById('gallery');
    if (matches.length === 0) {
        gallery.innerHTML = '<p>No similar images found within that threshold.</p>';
        return;
    }

    gallery.innerHTML = '';
    matches.forEach(match => {
        const m = match.media;
        const dist = match.distance;

        const card = document.createElement('a');
        card.className = 'post-card';
        card.href = `/post/${m.postId}`;

        const filename = m.localPath.split(/[\\/]/).pop();
        const thumbSrc = `/api/media/${m.id}/thumbnail`;

        card.innerHTML = `
            <div class="card-media-grid">
                <div class="card-media-item">
                    <img src="${thumbSrc}" loading="lazy">
                    <div class="card-overlay">Match Distance: ${dist}</div>
                </div>
            </div>
            <div class="card-footer">
                <small class="card-date">Media ID: ${m.id}</small><br>
                <small class="card-date">Rating: ${m.contentRating} / ${m.safetyRating}</small>
            </div>
        `;
        gallery.appendChild(card);
    });
}

document.addEventListener('click', (e) => {
    if (!searchBar.contains(e.target) && !searchResults.contains(e.target)) {
        searchResults.classList.add('hidden');
    }
    const filterBtn = document.getElementById('filterBtn');
    const filterDropdown = document.getElementById('filterDropdown');
    if (filterBtn && filterDropdown) {
        if (!filterBtn.contains(e.target) && !filterDropdown.contains(e.target)) {
            filterDropdown.classList.add('hidden');
        }
    }
});

window.onpopstate = () => {
    saveScrollState();
    currentUrlKey = window.location.pathname + window.location.search;
    router();
};

// --- INITIALIZE APP ---
async function initApp() {
    try {
        // 1. Check user login status automatically
        const userRes = await fetch('/api/auth/me');
        if (userRes.ok) {
            const userData = await userRes.json();
            if (userData.success) {
                currentUser = { loggedIn: true, username: userData.username, role: userData.role };
            }
        }

        // 2. Load Configs
        const res = await fetch('/api/config');
        if (res.ok) {
            const data = await res.json();
            validRatings.content = [...data.content, "Waiting"];
            validRatings.safety = [...data.safety, "Waiting"];
        }
    } catch (err) {
        console.error("Failed to load init endpoints", err);
    }

    // 3. Set up filters
    const savedFilters = localStorage.getItem('sandstar_filters');
    if (savedFilters) {
        try {
            const parsed = JSON.parse(savedFilters);
            selectedFilters = { ...selectedFilters, ...parsed };
        } catch(e) {}
    } else {
        if (canExecute()) {
            selectedFilters.content = [];
            selectedFilters.safety = [];
        } else {
            selectedFilters.content = ['KF'];
            selectedFilters.safety = ['Safe'];
        }
    }

    renderAuth();
    populateFilterMenu();
    router();
}

// Start app
initApp();