/* Bounded, resumable binary upload. No GeoJSON parsing and no whole-file ArrayBuffer. */
(function (root) {
  'use strict';
  const KEY = 'heatnet-upload-v1', MAX_SIZE = 3 * 1024 ** 3, DEFAULT_CHUNK = 16 * 1024 ** 2;
  const RETRYABLE = new Set([429, 500, 502, 503, 504]);
  const hex = (b) => Array.from(new Uint8Array(b), x => x.toString(16).padStart(2, '0')).join('');
  const sha = async (blob) => {
    if (!root.crypto || !root.crypto.subtle) throw new Error('Для защищённой загрузки откройте сайт через HTTPS');
    return hex(await root.crypto.subtle.digest('SHA-256', await blob.arrayBuffer()));
  };
  class UploadError extends Error { constructor(message, status = 0) { super(message); this.status = status; } }
  class HeatnetUpload {
    constructor(onProgress, env = root) {
      this.env = env; this.onProgress = onProgress; this.active = new Set(); this.inflight = new Map(); this.acked = new Set();
      this.paused = true; this.running = false;
      try { this.saved = JSON.parse(env.localStorage.getItem(KEY) || 'null'); } catch (_) { this.saved = null; }
    }
    remember(value) {
      // Fail before sending any bytes if browser persistence is unavailable.
      if (value) this.env.localStorage.setItem(KEY, JSON.stringify(value)); else this.env.localStorage.removeItem(KEY);
      this.saved = value;
    }
    emit(state, message) {
      const s = this.saved;
      let confirmed = 0;
      if (s) for (const i of this.acked) confirmed += Math.min(s.request.chunkSize, s.file.size - i * s.request.chunkSize);
      // A resumed session can already be complete after its final response was lost.
      if (s && state === 'COMPLETE') confirmed = s.file.size;
      const sent = Math.min(s ? s.file.size : 0, confirmed + [...this.inflight.values()].reduce((a,b) => a+b,0));
      this.onProgress({ state, message, confirmedBytes: confirmed, sentBytes: sent, totalBytes: s ? s.file.size : 0 });
    }
    pause() { this.paused = true; for (const request of this.active) request.abort(); }
    async retry(operation) {
      for (let attempt = 0; ; attempt++) {
        if (this.paused) throw new UploadError('Загрузка приостановлена');
        try { return await operation(); } catch (e) {
          if (this.paused || attempt >= 4 || (e.status && !RETRYABLE.has(e.status))) throw e;
          const delay = Math.min(10000, 1000 * 2 ** attempt);
          this.emit('RETRYING', `Связь прервана или сервер занят. Повтор через ${delay/1000} с`);
          await new Promise(resolve => this.env.setTimeout(resolve, delay));
        }
      }
    }
    async json(path, method = 'GET', body) {
      return this.retry(async () => {
        const controller = new this.env.AbortController(); this.active.add(controller);
        const timeout = this.env.setTimeout(() => controller.abort(), 120000);
        try {
          const r = await this.env.fetch('/api/v1/uploads' + path, { method,
            headers: { 'X-Upload-Token': this.saved.token, ...(body ? { 'Content-Type': 'application/json' } : {}) },
            body: body ? JSON.stringify(body) : undefined, signal: controller.signal, cache: 'no-store' });
          const result = r.status === 204 ? null : await r.json();
          if (!r.ok) throw new UploadError(result && result.message || `HTTP ${r.status}`, r.status);
          return result;
        } finally { this.env.clearTimeout(timeout); this.active.delete(controller); }
      });
    }
    async fingerprint(file) {
      const n = Math.min(64 * 1024, file.size), hashes = [];
      for (const offset of [0, Math.max(0, Math.floor((file.size-n)/2)), Math.max(0,file.size-n)])
        hashes.push(await sha(file.slice(offset, offset+n)));
      return sha(new Blob([JSON.stringify([file.name,file.size,file.lastModified,...hashes])]));
    }
    send(index, blob, checksum) {
      return this.retry(() => new Promise((resolve,reject) => {
        const xhr = new this.env.XMLHttpRequest(); this.active.add(xhr);
        const done = (err, value) => {
          this.active.delete(xhr); this.inflight.delete(index);
          if (err) reject(err); else resolve(value);
        };
        xhr.open('PUT', `/api/v1/uploads/${this.saved.request.uploadId}/parts/${index}`);
        xhr.responseType = 'json'; xhr.timeout = 300000;
        xhr.setRequestHeader('Content-Type','application/octet-stream');
        xhr.setRequestHeader('X-Upload-Token',this.saved.token); xhr.setRequestHeader('X-Part-SHA256',checksum);
        xhr.upload.onprogress = (event) => { this.inflight.set(index,Math.min(blob.size,event.loaded)); this.emit('UPLOADING','Передача файла'); };
        xhr.onerror = () => done(new UploadError('Сетевая ошибка'));
        xhr.ontimeout = () => done(new UploadError('Истекло время передачи части'));
        xhr.onabort = () => done(new UploadError('Передача приостановлена'));
        xhr.onload = () => xhr.status >= 200 && xhr.status < 300 ? done(null,xhr.response)
          : done(new UploadError(xhr.response && xhr.response.message || `HTTP ${xhr.status}`,xhr.status));
        xhr.send(blob);
      }));
    }
    async start(file, options) {
      if (this.running) throw new UploadError('Передача уже выполняется');
      if (file.size <= 0 || file.size > MAX_SIZE) throw new UploadError('Размер файла должен быть от 1 байта до 3 ГиБ');
      this.running = true; this.paused = false;
      this.acked.clear(); this.inflight.clear();
      try {
        this.emit('VERIFYING','Проверка выбранного файла');
        const identity = await this.fingerprint(file);
        if (this.saved) {
          const old = this.saved;
          if (old.file.name !== file.name || old.file.size !== file.size || old.file.lastModified !== file.lastModified || old.request.fingerprint !== identity)
            throw new UploadError('Выбран другой файл. Выберите исходный файл или отмените прежнюю загрузку');
          for (const key of ['mode','maxVariants','unknownRestrictionPolicy'])
            if (old.request[key] !== options[key]) throw new UploadError('Для продолжения сохраните исходные параметры расчёта');
        } else {
          const random = new Uint8Array(32); this.env.crypto.getRandomValues(random);
          this.remember({ token: hex(random), file: { name:file.name,size:file.size,lastModified:file.lastModified },
            request: { uploadId:this.env.crypto.randomUUID(), filename:file.name,totalSize:file.size,
              fingerprint:identity,chunkSize:options.chunkSize || DEFAULT_CHUNK,...options } });
        }
        const id = this.saved.request.uploadId;
        // Creation is itself idempotent: a lost create response cannot leak an unknown session.
        let status = await this.json('', 'POST', this.saved.request);
        if (status.state === 'COMPLETED') { const job = status.jobId; this.emit('COMPLETE','Файл принят'); this.remember(null); return job; }
        status = await this.json('/'+id);
        this.acked = new Set(status.parts.map(p => p.index));
        this.emit('VERIFYING','Проверка ранее подтверждённых частей');
        for (const part of status.parts) {
          if (this.paused) throw new UploadError('Загрузка приостановлена');
          const start = part.index * status.chunkSize;
          if (await sha(file.slice(start,Math.min(file.size,start+status.chunkSize))) !== part.sha256)
            throw new UploadError('Содержимое файла изменилось: подтверждённая часть отличается');
        }
        const missing = [];
        for (let i=0;i<status.partCount;i++) if (!this.acked.has(i)) missing.push(i);
        let next = 0, firstError;
        const worker = async () => {
          try {
            while (!this.paused && next < missing.length) {
              const index = missing[next++], start = index * status.chunkSize;
              const blob = file.slice(start,Math.min(file.size,start+status.chunkSize));
              const checksum = await sha(blob);
              await this.send(index,blob,checksum); this.acked.add(index); this.emit('UPLOADING','Часть сохранена сервером');
            }
          } catch (e) { if (!firstError) firstError=e; this.pause(); }
        };
        await Promise.all([worker(),worker()]);
        if (firstError) throw firstError;
        if (this.paused) throw new UploadError('Загрузка приостановлена');
        this.emit('VERIFYING','Файл передан. Сервер проверяет целостность');
        const done = await this.json('/'+id+'/complete','POST');
        this.emit('COMPLETE','Файл принят. Задание создано'); this.remember(null); return done.jobId;
      } catch (e) {
        this.pause(); this.inflight.clear();
        const hint = e.status === 404 || e.status === 410
          ? '. Сессия недоступна. Отмените прежнюю загрузку и начните новую'
          : this.saved ? '. Подтверждённые части сохранены' : '';
        this.emit('PAUSED',e.message + hint); throw e;
      } finally { this.running=false; }
    }
    async cancel() {
      this.pause();
      while (this.running) await new Promise(resolve => this.env.setTimeout(resolve, 25));
      if (!this.saved) return;
      this.paused=false;
      try { await this.json('/'+this.saved.request.uploadId,'DELETE'); }
      catch(e) { if (e.status !== 410 && e.status !== 404) throw e; }
      finally { this.paused=true; }
      this.emit('CANCELLED','Загрузка отменена'); this.remember(null); this.acked.clear();
    }
  }
  root.HeatnetUpload = HeatnetUpload;
  if (typeof module !== 'undefined') module.exports = { HeatnetUpload, UploadError };
})(typeof globalThis !== 'undefined' ? globalThis : this);
