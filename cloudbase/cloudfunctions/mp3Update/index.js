'use strict';

const cloudbase = require('@cloudbase/node-sdk');

const app = cloudbase.init({ env: cloudbase.SYMBOL_CURRENT_ENV });
const APK_FILE_ID =
  'cloud://chuya-d6gyub7awb35a8bf7.6368-chuya-d6gyub7awb35a8bf7-1300580117/releases/mp3-latest.apk';
const APP_VERSION = '4.1.0';
const APP_VERSION_CODE = 41;
const APK_SHA256 = '__APK_SHA256__';
const APK_SIZE_BYTES = 0;

exports.main = async (event = {}) => {
  try {
    if (event.httpMethod) return await handleHttp(event);
    if (event.action === 'health') {
      return { ok: true, service: 'mp3Update', version: APP_VERSION };
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
    return jsonResponse(200, { ok: true, service: 'mp3Update', version: APP_VERSION });
  }
  return jsonResponse(404, { error: '接口不存在' });
}

async function getVersionInfo() {
  if (!/^[a-f0-9]{64}$/.test(APK_SHA256) || APK_SIZE_BYTES <= 0) {
    throw new Error('Release metadata is incomplete');
  }
  const result = await app.getTempFileURL({ fileList: [APK_FILE_ID] });
  const file = result.fileList && result.fileList[0];
  if (!file || !file.tempFileURL || !file.tempFileURL.startsWith('https://')) {
    throw new Error('APK unavailable');
  }
  return {
    version: APP_VERSION,
    versionCode: APP_VERSION_CODE,
    apkUrl: file.tempFileURL,
    sha256: APK_SHA256,
    sizeBytes: APK_SIZE_BYTES,
    notes: '4.1.0：重做横屏驾驶界面与主题，强化长时间播放时的方向盘媒体键恢复，并加入应用内在线更新。',
  };
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
