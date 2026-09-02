// StreamFlix Provider for Nuvio — Hikari patched build.
// Changes vs upstream:
//  - No WebSocket dependency (hikari's QuickJS runtime has none) — TV uses the
//    deterministic  tv/<key>/s<s>/episode<e>.mkv  path on every server group.
//  - Runtime server liveness probe (HEAD): dead/removed servers (the old
//    wasabisys premium bucket returns NoSuchBucket) are filtered out so the
//    player is never handed a guaranteed-broken link. 405 (HEAD unsupported)
//    is treated as "still alive" and kept.
//  - All server groups (premium/movies/tv/download) are tried, deduped, and
//    only reachable ones are returned.
const cheerio = require('cheerio-without-node-native');

// Constants
const TMDB_API_KEY = "439c478a771f35c05022f9feabcca01c";
const STREAMFLIX_API_BASE = "https://api.streamflix.app";
const CONFIG_URL = `${STREAMFLIX_API_BASE}/config/config-streamflixapp.json`;
const DATA_URL = `${STREAMFLIX_API_BASE}/data.json`;

// Global cache
let cache = {
  config: null,
  configTimestamp: 0,
  data: null,
  dataTimestamp: 0,
  alive: null,
  aliveTimestamp: 0,
};
const CACHE_TTL = 1000 * 60 * 5; // 5 minutes

// Helper function for HTTP requests
function makeRequest(url, options = {}) {
  const defaultHeaders = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36',
    'Accept': 'application/json, text/plain, */*',
    'Accept-Language': 'en-US,en;q=0.5',
    'Connection': 'keep-alive'
  };

  return fetch(url, {
    ...options,
    headers: {
      ...defaultHeaders,
      ...options.headers
    }
  }).then(response => {
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }
    return response;
  });
}

// Get config data with caching
function getConfig() {
  const now = Date.now();
  if (cache.config && now - cache.configTimestamp < CACHE_TTL) {
    return Promise.resolve(cache.config);
  }

  return makeRequest(CONFIG_URL)
    .then(response => response.json())
    .then(json => {
      cache.config = json;
      cache.configTimestamp = now;
      return json;
    })
    .catch(error => {
      console.error('[StreamFlix] Failed to fetch config:', error.message);
      throw error;
    });
}

// Get data with caching
function getData() {
  const now = Date.now();
  if (cache.data && now - cache.dataTimestamp < CACHE_TTL) {
    return Promise.resolve(cache.data);
  }

  return makeRequest(DATA_URL)
    .then(response => response.json())
    .then(json => {
      cache.data = json;
      cache.dataTimestamp = now;
      return json;
    })
    .catch(error => {
      console.error('[StreamFlix] Failed to fetch data:', error.message);
      throw error;
    });
}

// Cheap liveness probe for a stream URL. Returns true when the server clearly
// serves it (2xx), false when the server/file is gone (4xx/5xx or unreachable).
// HEAD returning 405/501 is inconclusive — keep the server, don't hide it.
function serverAlive(url) {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (v) => { if (!settled) { settled = true; resolve(v); } };
    const timer = setTimeout(() => finish(true), 10000);
    try {
      fetch(url, { method: 'HEAD', headers: { 'Range': 'bytes=0-1' } }).then(r => {
        clearTimeout(timer);
        if (r.status === 405 || r.status === 501) finish(true); // HEAD unsupported
        else finish(r.status >= 200 && r.status < 300);
      }).catch(() => { clearTimeout(timer); finish(false); });
    } catch (e) {
      clearTimeout(timer);
      finish(false);
    }
  });
}

// Build the unique list of server base URLs (premium + movies + tv + download),
// deduped, stripping junk.
function collectServers(config) {
  const out = [];
  const seen = new Set();
  const groups = ['premium', 'movies', 'tv', 'download'];
  for (const g of groups) {
    const arr = config[g];
    if (!Array.isArray(arr)) continue;
    for (const base of arr) {
      if (!base || typeof base !== 'string') continue;
      const clean = base.trim().replace(/\/+$/, '');
      if (!clean.startsWith('http')) continue;
      if (seen.has(clean)) continue;
      seen.add(clean);
      out.push(clean);
    }
  }
  return out;
}

// Filter server list down to the ones that actually serve video, and return
// stream objects for every reachable base + suffix.
function buildLiveStreams(bases, suffix, baseTitle, quality) {
  return Promise.all(
    bases.map(base => serverAlive(base + '/' + suffix).then(alive => {
      if (!alive) return null;
      return {
        name: "StreamFlix",
        title: baseTitle,
        url: base + '/' + suffix,
        quality: quality,
        size: "Unknown",
        type: 'direct',
        headers: {
          'Referer': 'https://api.streamflix.app',
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
        }
      };
    }))
  ).then(list => list.filter(Boolean));
}

// Search for content by title
function searchContent(title, year, mediaType) {
  return getData()
    .then(data => {
      if (!data || !data.data) {
        throw new Error('Invalid data structure received');
      }

      const searchQuery = title.toLowerCase();
      const results = data.data.filter(item => {
        if (!item.moviename) return false;

        const itemTitle = item.moviename.toLowerCase();
        const titleWords = searchQuery.split(/\s+/);

        return titleWords.every(word => itemTitle.includes(word));
      });

      return results;
    });
}

// Find best match from search results
function findBestMatch(targetTitle, results) {
  if (!results || results.length === 0) {
    return null;
  }

  let bestMatch = null;
  let bestScore = 0;

  for (const result of results) {
    const score = calculateSimilarity(
      targetTitle.toLowerCase(),
      result.moviename.toLowerCase()
    );

    if (score > bestScore) {
      bestScore = score;
      bestMatch = result;
    }
  }

  return bestMatch;
}

// Calculate string similarity
function calculateSimilarity(str1, str2) {
  const words1 = str1.split(/\s+/);
  const words2 = str2.split(/\s+/);

  let matches = 0;
  for (const word of words1) {
    if (word.length > 2 && words2.some(w => w.includes(word) || word.includes(w))) {
      matches++;
    }
  }

  return matches / Math.max(words1.length, words2.length);
}

// Main function that Nuvio will call
function getStreams(tmdbId, mediaType = 'movie', seasonNum = null, episodeNum = null) {
  console.log(`[StreamFlix] Fetching streams for TMDB ID: ${tmdbId}, Type: ${mediaType}`);

  // Get TMDB info first
  const tmdbUrl = `https://api.themoviedb.org/3/${mediaType === 'tv' ? 'tv' : 'movie'}/${tmdbId}?api_key=${TMDB_API_KEY}`;

  return makeRequest(tmdbUrl)
    .then(response => response.json())
    .then(tmdbData => {
      const title = mediaType === 'tv' ? tmdbData.name : tmdbData.title;
      const year = mediaType === 'tv'
        ? tmdbData.first_air_date?.substring(0, 4)
        : tmdbData.release_date?.substring(0, 4);

      if (!title) {
        throw new Error('Could not extract title from TMDB response');
      }

      // Search for content
      return searchContent(title, year, mediaType)
        .then(searchResults => {
          if (searchResults.length === 0) {
            console.log('[StreamFlix] No search results found');
            return [];
          }

          const selectedResult = findBestMatch(title, searchResults);
          if (!selectedResult) {
            console.log('[StreamFlix] No suitable match found');
            return [];
          }

          // Get config for stream URLs
          return getConfig()
            .then(config => {
              const bases = collectServers(config);
              if (bases.length === 0) return [];

              if (mediaType === 'movie') {
                return processMovieStreams(selectedResult, bases);
              } else {
                return processTVStreams(selectedResult, bases, seasonNum, episodeNum);
              }
            });
        });
    })
    .catch(error => {
      console.error(`[StreamFlix] Error in getStreams: ${error.message}`);
      return [];
    });
}

// Process movie streams — every reachable server, deduped.
function processMovieStreams(movieData, bases) {
  if (!movieData.movielink) return Promise.resolve([]);
  const suffix = movieData.movielink.replace(/^\/+/, '');
  const title = `${movieData.moviename} - StreamFlix`;
  return buildLiveStreams(bases, suffix, title, "1080p");
}

// Process TV show streams — deterministic per-episode path on every reachable
// server (no WebSocket needed). Also tries the item's own movielink when set.
function processTVStreams(tvData, bases, seasonNum, episodeNum) {
  if (seasonNum === null || episodeNum === null) return Promise.resolve([]);

  const candidates = [];

  if (tvData.movielink) {
    candidates.push({
      suffix: tvData.movielink.replace(/^\/+/, ''),
      label: `${tvData.moviename} S${seasonNum}E${episodeNum}`,
      quality: "720p",
    });
  }

  candidates.push({
    suffix: `tv/${tvData.moviekey}/s${seasonNum}/episode${episodeNum}.mkv`,
    label: `${tvData.moviename} S${seasonNum}E${episodeNum}`,
    quality: "720p",
  });

  const unique = [];
  const seenSuffix = new Set();
  for (const c of candidates) {
    if (seenSuffix.has(c.suffix)) continue;
    seenSuffix.add(c.suffix);
    unique.push(c);
  }

  return Promise.all(
    unique.map(c => buildLiveStreams(bases, c.suffix, c.label, c.quality))
  ).then(groups => {
    const all = [];
    for (const g of groups) for (const s of g) all.push(s);
    return all;
  });
}

// Export for React Native
if (typeof module !== 'undefined' && module.exports) {
  module.exports = { getStreams };
} else {
  global.getStreams = getStreams;
}
