const crypto = require('crypto');
const fs = require('fs');
const fsp = require('fs/promises');
const https = require('https');
const path = require('path');
const { execFile } = require('child_process');
const { promisify } = require('util');

const execFileAsync = promisify(execFile);

function config() {
  const bucket = process.env.FASTBUG_OSS_BUCKET || 'zstt-test-tmp';
  let endpoint = String(process.env.FASTBUG_OSS_ENDPOINT || '').replace(/^https?:\/\//, '').replace(/\/$/, '');
  if (endpoint.startsWith(`${bucket}.`)) endpoint = endpoint.slice(bucket.length + 1);
  const accessKeyId = process.env.FASTBUG_OSS_ACCESS_KEY_ID || '';
  const accessKeySecret = process.env.FASTBUG_OSS_ACCESS_KEY_SECRET || '';
  const prefix = String(process.env.FASTBUG_OSS_PREFIX || 'fastbug').replace(/^\/+|\/+$/g, '');
  const missing = [];
  if (!endpoint) missing.push('FASTBUG_OSS_ENDPOINT');
  if (!accessKeyId) missing.push('FASTBUG_OSS_ACCESS_KEY_ID');
  if (!accessKeySecret) missing.push('FASTBUG_OSS_ACCESS_KEY_SECRET');
  if (missing.length) throw new Error(`OSS 配置缺失：${missing.join('、')}`);
  return { endpoint, accessKeyId, accessKeySecret, bucket, prefix };
}

function hmac(secret, text) { return crypto.createHmac('sha1', secret).update(text).digest('base64'); }
function encodedPath(key) { return '/' + key.split('/').map(encodeURIComponent).join('/'); }

function putObject({ endpoint, bucket, accessKeyId, accessKeySecret }, objectKey, file) {
  return new Promise((resolve, reject) => {
    const date = new Date().toUTCString();
    const contentType = 'application/zip';
    const resource = `/${bucket}/${objectKey}`;
    const signature = hmac(accessKeySecret, `PUT\n\n${contentType}\n${date}\n${resource}`);
    const request = https.request({
      hostname: `${bucket}.${endpoint}`,
      method: 'PUT',
      path: encodedPath(objectKey),
      headers: { Date: date, 'Content-Type': contentType, Authorization: `OSS ${accessKeyId}:${signature}`, 'Content-Length': fs.statSync(file).size }
    }, response => {
      let responseBody = '';
      response.setEncoding('utf8');
      response.on('data', chunk => { responseBody += chunk; });
      response.on('end', () => {
      if (response.statusCode >= 200 && response.statusCode < 300) resolve();
      else {
        const code = responseBody.match(/<Code>([^<]+)<\/Code>/)?.[1];
        const message = responseBody.match(/<Message>([^<]+)<\/Message>/)?.[1];
        reject(new Error(`OSS 上传失败（HTTP ${response.statusCode}${code ? `，${code}` : ''}${message ? `：${message}` : ''}）`));
      }
      });
    });
    request.on('error', reject);
    fs.createReadStream(file).on('error', reject).pipe(request);
  });
}

function signedDownloadUrl({ endpoint, bucket, accessKeyId, accessKeySecret }, objectKey, expiresInSeconds = 7 * 24 * 60 * 60) {
  const expires = Math.floor(Date.now() / 1000) + expiresInSeconds;
  const resource = `/${bucket}/${objectKey}`;
  const signature = hmac(accessKeySecret, `GET\n\n\n${expires}\n${resource}`);
  return `https://${bucket}.${endpoint}${encodedPath(objectKey)}?OSSAccessKeyId=${encodeURIComponent(accessKeyId)}&Expires=${expires}&Signature=${encodeURIComponent(signature)}`;
}

async function uploadEvidencePackage(captureDir, folder) {
  const oss = config();
  const temporaryDir = path.join(path.dirname(path.dirname(captureDir)), 'upload-temp');
  await fsp.mkdir(temporaryDir, { recursive: true });
  const archive = path.join(temporaryDir, `${folder}.zip`);
  await fsp.rm(archive, { force: true });
  await execFileAsync('tar.exe', ['-a', '-c', '-f', archive, '-C', path.dirname(captureDir), path.basename(captureDir)], { windowsHide: true, maxBuffer: 1024 * 1024 });
  const objectKey = `${oss.prefix}/${folder}/evidence.zip`;
  await putObject(oss, objectKey, archive);
  const stat = await fsp.stat(archive);
  return {
    bucket: oss.bucket,
    objectKey,
    size: stat.size,
    downloadUrl: signedDownloadUrl(oss, objectKey),
    expiresAt: new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString()
  };
}

module.exports = { uploadEvidencePackage };
