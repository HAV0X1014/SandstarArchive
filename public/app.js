const app = document.getElementById('app');
const authSection = document.getElementById('authSection');
const LIMIT = 24;
let isLoading = false;
const searchBar = document.getElementById('searchBar');
const searchResults = document.getElementById('searchResults');
let searchTimeout;

// --- STATE MANAGEMENT & CACHING ---
let currentUrlKey = window.location.pathname + window.location.search;
let pageCache = {};
let accountCache = {};
let validRatings = { content: [], safety: [] }; // Stores config from API

let selectedFilters = {
    content: [],
    safety: [],
    sort: 'newest'
};

function saveScrollState() {
    if (!pageCache[currentUrlKey]) pageCache[currentUrlKey] = {};
    pageCache[currentUrlKey].scrollY = window.scrollY;
}

// --- AUTH LOGIC ---
let adminKey = localStorage.getItem('adminKey');

function renderAuth() {
    if (adminKey) {
        authSection.innerHTML = `<button class="btn" onclick="logout()">Logout</button>`;
    } else {
        authSection.innerHTML = `<button class="btn" onclick="showLogin()">Login</button>`;
    }
}

async function showLogin() {
    const key = prompt("Enter Admin Code:");
    if (!key) return;
    const resp = await fetch('/api/auth/verify', { method: 'POST', body: key });
    if (resp.ok) {
        localStorage.setItem('adminKey', key);
        adminKey = key;
        location.reload();
    } else {
        alert("Invalid Key");
    }
}

function logout() {
    localStorage.removeItem('adminKey');
    location.reload();
}

// --- ROUTER ---
function router() {
    const path = window.location.pathname;
    const urlParams = new URLSearchParams(window.location.search);
    const page = parseInt(urlParams.get('page')) || 1;

    app.innerHTML = '';
    document.body.classList.remove('detail-view-active');

    if (path === '/' || path === '/index.html') {
        renderGlobalFeed(page);
    } else if (path === '/artists') {
        renderAllArtistsView();
        window.scrollTo(0, 0);
    } else if (path.startsWith('/artist/')) {
        renderArtistView(path.split('/')[2]);
        window.scrollTo(0, 0);
    } else if (path.startsWith('/account/')) {
        renderAccountView(path.split('/')[2], page);
    } else if (path.startsWith('/post/')) {
        document.body.classList.add('detail-view-active');
        renderPostDetail(path.split('/')[2]);
        window.scrollTo(0, 0);
    } else if (path.startsWith('/media/')) {
        document.body.classList.add('detail-view-active');
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

        container.innerHTML = artists.map(a => `
            <div class="post-card" onclick="navigateTo('/artist/${encodeURIComponent(a.name)}')" style="padding: 15px; display: block; height: auto;">
                <h3 class="text-primary">${a.name}</h3>
                <p style="font-size: 0.85rem; color: var(--text-muted); margin-top: 5px;">${a.description || 'No description provided.'}</p>
            </div>
        `).join('');
    } catch (e) {
        document.getElementById('artistsList').innerHTML = '<p>Error loading artists.</p>';
    }
}

async function renderArtistView(name) {
    const res = await fetch(`/api/artists/name/${name}`);
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
                    <div class="post-card" onclick="navigateTo('/account/${acc.twitterId}')">
                        <div class="card-footer" style="height: 100%;">
                            <strong class="text-primary">@${acc.screenName}</strong><br>${acc.displayName}<br>
                            <small>Status: ${acc.accountStatus}</small><br>
                            <small>Safety Rating: ${acc.safetyRating}</small>
                        </div>
                    </div>
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
    const res = await fetch(`/api/post/${postId}`);
    const post = await res.json();
    const columns = post.media.length > 1 ? 'repeat(2, auto)' : 'auto';

    app.innerHTML = `
    <div class="detail-view">
        <div class="detail-header">
            <a href="javascript:void(0)" onclick="history.back()" class="text-primary">← Back</a>
        </div>

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

    let captionContent = !adminKey
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
                        <div class="detail-meta">
                            <span>Media ID: ${m.id}</span><span>•</span><span>${m.mediaType}</span>
                        </div>
                        <div style="margin-bottom: 6px;">
                            <a href="javascript:void(0)" onclick="navigateTo('/post/${m.postId}')" class="text-primary" style="font-size: 1rem; font-weight: bold;">← View Post</a>
                        </div>
                        <a href="https://x.com/i/status/${m.postId}" target="_blank" style="color: var(--text-muted); font-size: 0.85rem;">Original Link ↗</a>
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

    // Apply Filters & Sorting
    const cParams = selectedFilters.content.map(c => `c=${encodeURIComponent(c)}`).join('&');
    const sParams = selectedFilters.safety.map(s => `s=${encodeURIComponent(s)}`).join('&');
    const sortParam = `sort=${selectedFilters.sort}`;

    const filterQuery = [cParams, sParams, sortParam].filter(x => x !== '').join('&');

    const url = twitterId
        ? `/api/posts/${twitterId}?limit=${LIMIT}&offset=${offset}&${filterQuery}`
        : `/api/posts/global?limit=${LIMIT}&offset=${offset}&${filterQuery}`;

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
            const card = document.createElement('div');
            card.className = 'post-card';
            card.addEventListener('click', function(e) {
                if (!e.target.closest('select')) navigateTo(`/post/${post.postId}`);
            });

            let mediaGrid = '';
            for (let i = 0; i < post.media.length; i += 2) {
                const rowMedia = post.media.slice(i, i + 2);
                let rowHtml = '';

                rowMedia.forEach((m) => {
                    const src = `/images/${m.contentRating}/${m.safetyRating}/${m.localPath.split(/[\\/]/).pop()}`;

                    rowHtml += `
                        <div class="card-media-item">
                            ${m.mediaType.includes('mp4') ? `<video src="${src}" controls></video>` : `<img src="${m.originalUrl.replace("orig","small")}" />`}
                            <div class="card-overlay">
                                ${adminKey ? `
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
                    ${adminKey ? `
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

    // Save configuration to localStorage
    localStorage.setItem('sandstar_filters', JSON.stringify(selectedFilters));

    pageCache = {}; // Clear cache so we retrieve new filtered items
    toggleFilters();
    router(); // Re-render page
}

// --- HELPERS ---

function generateOptionsHtml(ratingType, currentValue) {
    const options = ratingType === 'Content' ? validRatings.content : validRatings.safety;
    // Don't let users assign 'Waiting' to posts manually
    const selectableOptions = options.filter(opt => opt !== 'Waiting');
    if (currentValue === 'Waiting' && !selectableOptions.includes('Waiting')) {
        selectableOptions.unshift('Waiting');
    }

    return selectableOptions.map(opt => `<option value="${opt}" ${currentValue === opt ? 'selected' : ''}>${opt}</option>`).join('');
}

function renderRatingControls(id, type, currentContent, currentSafety) {
    if (!adminKey) return `<div class="controls">Content: ${currentContent}&nbsp; Safety: ${currentSafety}</div>`;
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
    const res = await fetch(`/api/media/caption?mediaId=${mediaId}&caption=${encodeURIComponent(captionText)}`, {
        method: 'POST', headers: { 'Authorization': adminKey }
    });
    if (res.ok) alert("Caption updated!");
    else alert("Failed to update caption. Are you logged in?");
}

async function updateRating(id, targetType, ratingType, value) {
    const endpoint = targetType === 'post' ? '/api/rate/post' : '/api/rate/media';
    const paramName = targetType === 'post' ? 'postId' : 'mediaId';

    await fetch(`${endpoint}?${paramName}=${id}&type=${ratingType}&value=${value}`, {
        method: 'POST', headers: { 'Authorization': adminKey }
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
            fetch(`/api/artists/search?q=${encodeURIComponent(query)}`),
            fetch(`/api/accounts/search?q=${encodeURIComponent(query)}`)
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
            // Also added encodeURIComponent here to support artist names with spaces/special characters
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
        const res = await fetch('/api/config');
        if (res.ok) {
            const data = await res.json();
            // Attach "Waiting" visually for filtering options
            validRatings.content = [...data.content, "Waiting"];
            validRatings.safety = [...data.safety, "Waiting"];
        }
    } catch (err) {
        console.error("Failed to load config endpoints", err);
    }

    // Attempt to load filters from persistence
    const savedFilters = localStorage.getItem('sandstar_filters');
    if (savedFilters) {
        try {
            const parsed = JSON.parse(savedFilters);
            selectedFilters = { ...selectedFilters, ...parsed };
        } catch(e) {}
    } else {
        // Fallback default state
        if (adminKey) {
            // Admins get everything checked by default (or empty to mean all)
            selectedFilters.content = [];
            selectedFilters.safety = [];
        } else {
            // Guests only get KF and Safe by default
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