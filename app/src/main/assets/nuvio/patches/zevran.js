// Zevran. (VAplayer) provider for Nuvio — Hikari patched build.
// Changes vs upstream:
//  - The API is picky about the Referer/Origin it receives: it must be the
//    PLAYER EMBED page (nextgencloudfabric.com/embed/movie/<id> or
//    /embed/tv/<id>/<season>/<episode>), not the bare site root. Upstream sent
//    the root, so the API answered with no stream_urls and VAplayer always
//    failed with "no sources".
//  - It also accepts a `tmdb` id instead of `imdb`, so this build falls back to
//    the tmdb id when the imdb lookup (or the imdb query) yields nothing.
//  - status_code is tolerated as either a number (200) or a string ("200"), and
//    stream_urls may be a string, an array of strings, or an array of objects.
//  - The returned sources carry the same Referer/Origin/UA headers the CDN
//    expects, so playback works instead of erroring out.
"use strict";

const TMDB_API_KEY = "307b7b8ef035c6aa336900aef4e203bd";
const TMDB_BASE_URL = "https://api.themoviedb.org/3";
const VAPLAYER_API = "https://streamdata.vaplayer.ru/api.php";
const PLAYER_ORIGIN = "https://nextgencloudfabric.com";
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

function requestHeaders(embedUrl) {
  return {
    "User-Agent": UA,
    "Referer": embedUrl,
    "Origin": PLAYER_ORIGIN,
    "Accept": "*/*"
  };
}

async function fetchImdbId(tmdbId, mediaType) {
  try {
    const endpoint = mediaType === "tv" ? "tv" : "movie";
    const url = `${TMDB_BASE_URL}/${endpoint}/${tmdbId}?api_key=${TMDB_API_KEY}&append_to_response=external_ids`;
    const res = await fetch(url, { headers: { "User-Agent": UA, "Accept": "application/json" } });
    if (!res.ok) return null;
    const data = await res.json();
    return data?.external_ids?.imdb_id || null;
  } catch (e) {
    return null;
  }
}

function buildQuery(idKey, idValue, type, season, episode) {
  let q = `${idKey}=${encodeURIComponent(idValue)}&type=${type}`;
  if (type === "tv" && season != null && episode != null) {
    q += `&season=${season}&episode=${episode}`;
  }
  return q;
}

function embedReferer(idValue, type, season, episode) {
  if (type === "tv" && season != null && episode != null) {
    return `${PLAYER_ORIGIN}/embed/tv/${idValue}/${season}/${episode}`;
  }
  return `${PLAYER_ORIGIN}/embed/movie/${idValue}`;
}

async function queryApi(idKey, idValue, type, season, episode) {
  const referer = embedReferer(idValue, type, season, episode);
  const url = `${VAPLAYER_API}?${buildQuery(idKey, idValue, type, season, episode)}`;
  try {
    const res = await fetch(url, { headers: requestHeaders(referer) });
    if (!res.ok) return null;
    const text = await res.text();
    let data;
    try { data = JSON.parse(text); } catch (e) { return null; }
    if (!data || data.status_code === "error") return null;
    if (data.status_code != null && String(data.status_code) !== "200") return null;
    if (!data.data) return null;
    return data;
  } catch (e) {
    return null;
  }
}

function getQuality(streamObj, topLevelData, fallbackUrl = "") {
  const fields = [
    streamObj.quality, streamObj.label, streamObj.res,
    streamObj.bitrate, streamObj.type, streamObj.resolution,
    topLevelData.quality, topLevelData.label, topLevelData.res,
    topLevelData.bitrate, topLevelData.type,
    streamObj.url, fallbackUrl
  ].filter(Boolean).join(" ").toLowerCase();

  if (fields.includes("2160") || fields.includes("4k") || fields.includes("uhd")) return "2160p";
  if (fields.includes("1440")) return "1440p";
  if (fields.includes("1080")) return "1080p";
  if (fields.includes("720")) return "720p";
  if (fields.includes("480")) return "480p";
  if (fields.includes("360")) return "360p";
  return "1080p";
}

async function getStreams(tmdbId, mediaType, season, episode) {
  try {
    if (mediaType === "tv" && (season == null || episode == null)) return [];
    const type = mediaType === "tv" ? "tv" : "movie";

    const imdbId = await fetchImdbId(tmdbId, mediaType);

    let data = null;
    if (imdbId) data = await queryApi("imdb", imdbId, type, season, episode);
    if (!data) data = await queryApi("tmdb", tmdbId, type, season, episode);
    if (!data) return [];

    const raw = data.data.stream_urls;
    let rawItems = [];
    if (Array.isArray(raw)) {
      rawItems = raw;
    } else if (raw && typeof raw === "object") {
      rawItems = Object.values(raw);
    } else if (typeof raw === "string" && raw) {
      rawItems = [raw];
    } else if (data.data.stream_url) {
      rawItems = [data.data.stream_url];
    }

    const streamMap = new Map();
    for (const item of rawItems) {
      let streamUrl = "";
      let streamObj = {};
      if (typeof item === "string") {
        streamUrl = item;
      } else if (item && typeof item === "object") {
        streamUrl = item.url || item.file || item.src || "";
        streamObj = item;
      } else {
        continue;
      }
      if (!streamUrl) continue;
      const cleanUrl = streamUrl.trim().replace(/\/+$/, "");
      if (!streamMap.has(cleanUrl)) {
        streamMap.set(cleanUrl, {
          url: cleanUrl,
          quality: getQuality(streamObj, data.data, cleanUrl)
        });
      }
    }

    if (streamMap.size === 0) return [];

    const subs = data.data.default_subs || data.default_subs || [];
    const subtitles = (Array.isArray(subs) ? subs : [])
      .filter(s => s.url)
      .map(s => ({
        url: s.url,
        language: s.code || s.lang || "unknown",
        name: s.lang || s.code || "Unknown"
      }));

    return [...streamMap.values()].map((stream, i) => ({
      name: "VAplayer",
      title: "VAplayer",
      url: stream.url,
      quality: stream.quality,
      headers: {
        "User-Agent": UA,
        "Referer": `${PLAYER_ORIGIN}/`,
        "Origin": PLAYER_ORIGIN
      },
      subtitles
    }));
  } catch (e) {
    return [];
  }
}

module.exports = { getStreams };
