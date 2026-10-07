'use strict';

const RELEASE_API = 'https://api.github.com/repos/kangshunkang-del/mp3player/releases/latest';
const RELEASE_METADATA_ASSET = 'update-info.json';

exports.main = async (event = {}) => {
  try {
    if (event.httpMethod) return await handleHttp(event);
    if (event.action === 'health') {
      return { ok: true, service: 'mp3Update' };
    }
    if (event.action === 'getVersion') {
      return { ok: true, data: await getVersionInfo() };
    }
    return { ok: false, error: 'Unsupported action' };
  } catch (error) {
    console.error('mp3Update error', {
      name: error && error.name,
      message: error && error.message,
    });
    if (event.httpMethod) return jsonResponse(500, { error: '更新服务暂时不可用，请稍后重试' });
    return { ok: false, error: '更新服务暂时不可用，请稍后重试' };
  }
};

async function handleHttp(event) {
  const method = String(event.httpMethod || 'GET').toUpperCase();
  const path = String(event.path || '/').replace(/\/+$/, '') || '/';

  if (method === 'OPTIONS') return emptyResponse(204);
  if (method === 'GET' && path.endsWith('/version.json')) {
    return jsonResponse(200, await getVersionInfo());
  }
  if (method === 'GET' && path === '/') {
    return jsonResponse(200, { ok: true, service: 'mp3Update' });
  }
  return jsonResponse(404, { error: '接口不存在' });
}

async function getVersionInfo() {
  const releaseResponse = await fetch(RELEASE_API, {
    headers: { accept: 'application/vnd.github+json', 'user-agent': 'mp3player-update' },
  });
  if (!releaseResponse.ok) throw new Error(`GitHub release lookup failed: ${releaseResponse.status}`);
  const release = await releaseResponse.json();
  if (release.draft || release.prerelease) throw new Error('No stable release is published');
  const metadataAsset = (release.assets || []).find((asset) => asset.name === RELEASE_METADATA_ASSET);
  if (!metadataAsset || !metadataAsset.browser_download_url) throw new Error('Release metadata asset is missing');

  const metadataResponse = await fetch(metadataAsset.browser_download_url, {
    headers: { accept: 'application/octet-stream', 'user-agent': 'mp3player-update' },
  });
  if (!metadataResponse.ok) throw new Error(`Release metadata download failed: ${metadataResponse.status}`);
  const metadata = await metadataResponse.json();
  if (!metadata.apkUrl || !metadata.apkUrl.startsWith('https://') ||
      !/^[a-f0-9]{64}$/.test(metadata.sha256) || !Number.isSafeInteger(metadata.sizeBytes) ||
      metadata.sizeBytes <= 0 || !Number.isSafeInteger(metadata.versionCode) || metadata.versionCode <= 0) {
    throw new Error('Release metadata is invalid');
  }
  return metadata;
}

function corsHeaders() {
  return {
    'access-control-allow-origin': '*',
    'access-control-allow-methods': 'GET, OPTIONS',
    'access-control-allow-headers': 'content-type',
  };
}

function jsonResponse(statusCode, body) {
  return {
    statusCode,
    headers: {
      ...corsHeaders(),
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
    },
    body: JSON.stringify(body),
  };
}

function emptyResponse(statusCode) {
  return { statusCode, headers: corsHeaders(), body: '' };
}
