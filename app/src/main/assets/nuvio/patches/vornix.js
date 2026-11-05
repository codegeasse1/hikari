"use strict"

const TMDB_KEY = "307b7b8ef035c6aa336900aef4e203bd";
const BASE_URL = "https://new3.moviesdrive.christmas";
const REQUEST_HEADERS = {
  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
  "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
  "Accept-Language": "en-US,en;q=0.9",
  "Cache-Control": "max-age=0",
  "Connection": "keep-alive",
};

const HUBCLOUD_SERVER_LABELS = new Map([
  ["r2.dev", "Direct R2"],
  ["workers.dev", "ZipDisk"],
  ["fsl server", "FSL"],
  ["s3 server", "S3"],
  ["fslv2", "FSLv2"],
  ["mega server", "Mega"],
]);

async function fetchTmdbMeta(tmdbId, mediaType) {
  try {
    const type = mediaType === "tv" ? "tv" : "movie";
    const res = await fetch(
      `https://api.themoviedb.org/3/${type}/${tmdbId}?api_key=${TMDB_KEY}&append_to_response=external_ids`,
      { headers: { "User-Agent": "Mozilla/5.0", "Accept": "application/json" } }
    );
    if (!res.ok) return null;
    const data = await res.json();
    return {
      title: data.title || data.name || "",
      imdbId: data.external_ids?.imdb_id ?? null,
    };
  } catch {
    return null;
  }
}

async function extractHubCloudLinks(url, referer) {
  try {
    let currentUrl = url.replace("hubcloud.ink", "hubcloud.dad");
    let html = await (await fetch(currentUrl, { headers: { ...REQUEST_HEADERS, Referer: referer } })).text();

    if (!currentUrl.includes("hubcloud.php")) {
      const $first = cheerio.load(html);
      let nextUrl = $first("#download").attr("href")
        || (html.match(/var url = '([^']*)'/) || [])[1]
        || "";

      if (nextUrl) {
        if (!nextUrl.startsWith("http")) {
          const base = new URL(currentUrl);
          nextUrl = `${base.protocol}//${base.hostname}/${nextUrl.replace(/^\//, "")}`;
        }
        html = await (await fetch(nextUrl, { headers: { ...REQUEST_HEADERS, Referer: currentUrl } })).text();
        currentUrl = nextUrl;
      }
    }

    const $ = cheerio.load(html);
    const size = $("i#size").text().trim();
    const header = $("div.card-header").text().trim();
    const qMatch = header.match(/(\d{3,4})[pP]/);
    const quality = qMatch ? parseInt(qMatch[1]) : 1080;

    const results = [];
    for (const el of $("a.btn").get()) {
      const link = $(el).attr("href") || "";
      const text = $(el).text().toLowerCase();

      const isValidLink =
        text.includes("download") ||
        link.includes("pixel.hubcloud.cx") ||
        link.includes("pixeldrain.dev") ||
        link.includes("hubcloud.cx/tg") ||
        link.includes("r2.dev") ||
        link.includes("fsl") ||
        link.includes("s3");

      if (!isValidLink) continue;

      let serverLabel = "HubCloud";
      for (const [key, label] of HUBCLOUD_SERVER_LABELS) {
        if (link.includes(key) || text.includes(key)) { serverLabel = label; break; }
      }
      if (link.includes("pixel.hubcloud.cx")) serverLabel = "Pixel";
      else if (link.includes("pixeldrain.dev")) serverLabel = "PixelServer";
      else if (link.includes("hubcloud.cx/tg")) serverLabel = "Telegram";

      results.push({ name: serverLabel, quality, url: link, size });
    }

    return results;
  } catch {
    return [];
  }
}

async function dispatchExtractor(url, referer) {
  try {
    const host = new URL(url).hostname;
    if (host.includes("hubcloud")) return extractHubCloudLinks(url, referer);
    return [];
  } catch {
    return [];
  }
}

async function resolveServerLinks(url) {
  try {
    const html = await (await fetch(url, { headers: { ...REQUEST_HEADERS } })).text();

    if (url.includes("search-recover.php")) {
      const qMatch = html.match(/const Q_INITIAL\s*=\s*"([^"]+)"/);
      const tokenMatch = html.match(/const FROM_AC_TOKEN\s*=\s*"([^"]+)"/);

      if (qMatch && tokenMatch) {
        const base = url.split("?")[0];
        const params = new URLSearchParams({ api: "search", q: qMatch[1], page: "1", from_ac: tokenMatch[1] });
        const data = await (await fetch(`${base}?${params}`, {
          headers: { ...REQUEST_HEADERS, Accept: "application/json" },
        })).json();
        if (data.hits) return data.hits.map(h => h.url).filter(Boolean);
      }
    }

    const $ = cheerio.load(html);
    return $("a[href]")
      .map((_, el) => $(el).attr("href"))
      .get()
      .filter(href => /hubcloud/i.test(href));
  } catch {
    return [];
  }
}

function buildStreamEntry(stream, displayTitle) {
  return {
    name: `MoviesDrive • ${stream.name}`,
    title: `MoviesDrive • ${stream.name}`,
    url: stream.url,
    quality: stream.quality,
    ...(stream.size ? { size: stream.size } : {}),
  };
}

function parseSizeBytes(sizeStr) {
  const match = (sizeStr || "").match(/([\d.]+)\s*(GB|MB|KB)/i);
  if (!match) return 0;
  const val = parseFloat(match[1]);
  const unit = match[2].toUpperCase();
  if (unit === "GB") return val * 1073741824;
  if (unit === "MB") return val * 1048576;
  if (unit === "KB") return val * 1024;
  return 0;
}

function applyStreamLimits(streams) {
  const seen = new Set();
  const deduped = streams.filter(s => s.url && s.quality >= 1080 && !seen.has(s.url) && seen.add(s.url));

  const above1080 = deduped
    .filter(s => s.quality > 1080)
    .sort((a, b) => parseSizeBytes(b.size) - parseSizeBytes(a.size))
    .slice(0, 3);
  const at1080 = deduped
    .filter(s => s.quality === 1080)
    .sort((a, b) => parseSizeBytes(b.size) - parseSizeBytes(a.size))
    .slice(0, 2);

  return [...above1080, ...at1080];
}

async function resolveMovieStreams(downloadLinks, referer, displayTitle) {
  const results = [];
  for (const link of [...new Set(downloadLinks)]) {
    const serverUrls = await resolveServerLinks(link);
    const groups = await Promise.all(serverUrls.map(u => dispatchExtractor(u, referer)));
    for (const group of groups) {
      for (const s of group) results.push(buildStreamEntry(s, displayTitle));
    }
  }
  return results;
}

async function resolveEpisodeStreams(pageUrl, season, episode, displayTitle) {
  try {
    const html = await (await fetch(pageUrl, { headers: REQUEST_HEADERS })).text();
    const $ = cheerio.load(html);
    const epRegex = new RegExp(`Ep${String(episode).padStart(2, "0")}|Ep${episode}`, "i");
    const results = [];

    const epEntries = $("h5").filter((_, el) => epRegex.test($(el).text())).get();
    for (const entry of epEntries) {
      const epLinks = [
        $(entry).next().find("a").attr("href"),
        $(entry).next().next().find("a").attr("href"),
      ].filter(Boolean);

      const groups = await Promise.all(epLinks.map(u => dispatchExtractor(u, pageUrl)));
      for (const group of groups) {
        for (const s of group) results.push(buildStreamEntry(s, displayTitle));
      }
    }

    return results;
  } catch {
    return [];
  }
}

async function getStreams(tmdbId, mediaType, season, episode) {
  if (mediaType === "tv" && season == null) return [];

  const meta = await fetchTmdbMeta(tmdbId, mediaType);
  if (!meta?.imdbId) return [];

  const { title, imdbId } = meta;

  try {
    const searchRes = await fetch(`${BASE_URL}/search.php?q=${imdbId}`, { headers: REQUEST_HEADERS });
    if (!searchRes.ok) return [];

    const searchData = await searchRes.json();
    const match = (searchData.hits || [])
      .map(h => h.document)
      .find(d => d.imdb_id === imdbId);

    if (!match) return [];

    const pageUrl = match.permalink.startsWith("http") ? match.permalink : `${BASE_URL}${match.permalink}`;
    const pageHtml = await (await fetch(pageUrl, { headers: REQUEST_HEADERS })).text();
    const $ = cheerio.load(pageHtml);

    let streams = [];

    if (mediaType === "movie") {
      const downloadLinks = $("h5 > a").map((_, el) => $(el).attr("href")).get();
      streams = await resolveMovieStreams(downloadLinks, pageUrl, title);
    } else {
      const seasonRegex = new RegExp(`Season ${season}`, "i");
      const seasonEntries = $("h5").filter((_, el) => seasonRegex.test($(el).text())).get();
      const displayTitle = `${title} S${season}E${episode}`;

      for (const entry of seasonEntries) {
        const seasonPageUrl = $(entry).next().find("a").attr("href");
        if (!seasonPageUrl) continue;
        const epStreams = await resolveEpisodeStreams(seasonPageUrl, season, episode, displayTitle);
        streams.push(...epStreams);
      }
    }

    return applyStreamLimits(streams);
  } catch {
    return [];
  }
}

module.exports = { getStreams };