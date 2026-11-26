@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package app.coredump.platform

import kotlin.js.JsAny

/*
 * The browser side of the web app: small JavaScript pieces called from Kotlin (storage, sound, camera, files, share,
 * back button, page address). Bytes cross over as strings of char codes 0..255 (one call instead of one per byte).
 */

// ------------------------------------------------------------------ clock, page

internal fun jsNow(): Double = js("Date.now()")

internal fun jsHref(): String = js("String(window.location.href)")

/** Drops "#…" (sign-in links put their tokens there) from the address bar without reloading. */
internal fun jsClearHash(): Unit = js("""{
    try { history.replaceState(history.state, '', window.location.pathname + window.location.search); } catch (e) {}
}""")

internal fun jsUserAgent(): String = js("String(navigator.userAgent || '')")

/** Tab hidden (false) / shown again (true). */
internal fun jsOnVisibility(cb: (Boolean) -> Unit): Unit = js("""{
    document.addEventListener('visibilitychange', function () { cb(document.visibilityState === 'visible'); });
}""")

/** Hides the "loading…" text of index.html once the app draws. */
internal fun jsBooted(): Unit = js("""{
    var el = document.getElementById('boot'); if (el) el.remove();
}""")

internal fun jsColorScheme(dark: Boolean): Unit = js("""{
    try { document.documentElement.style.colorScheme = dark ? 'dark' : 'light'; } catch (e) {}
}""")

// ------------------------------------------------------------------ storage (IndexedDB, localStorage when there is none)

/** Reads every stored file once at start: onItem(name, text) for each, then onDone(true = IndexedDB works). */
internal fun jsStoreLoad(onItem: (String, String) -> Unit, onDone: (Boolean) -> Unit): Unit = js("""{
    var fallback = function () {
        try {
            for (var i = 0; i < localStorage.length; i++) {
                var k = localStorage.key(i);
                if (k && k.indexOf('cd:') === 0) onItem(k.substring(3), String(localStorage.getItem(k)));
            }
        } catch (e) {}
        onDone(false);
    };
    try {
        if (!window.indexedDB) { fallback(); return; }
        var req = indexedDB.open('coredump', 1);
        req.onupgradeneeded = function () { req.result.createObjectStore('files'); };
        req.onerror = function () { fallback(); };
        req.onblocked = function () { fallback(); };
        req.onsuccess = function () {
            var db = req.result;
            globalThis.__cdDb = db;
            try {
                var cur = db.transaction('files', 'readonly').objectStore('files').openCursor();
                cur.onsuccess = function () {
                    var c = cur.result;
                    if (c) { onItem(String(c.key), String(c.value)); c.continue(); } else onDone(true);
                };
                cur.onerror = function () { onDone(true); };
            } catch (e) { onDone(true); }
        };
    } catch (e) { fallback(); }
}""")

internal fun jsStorePut(name: String, text: String, idb: Boolean): Unit = js("""{
    try {
        if (idb && globalThis.__cdDb) globalThis.__cdDb.transaction('files', 'readwrite').objectStore('files').put(text, name);
        else localStorage.setItem('cd:' + name, text);
    } catch (e) { console.log('STORE put failed ' + name + ': ' + e); }
}""")

internal fun jsStoreDelete(name: String, idb: Boolean): Unit = js("""{
    try {
        if (idb && globalThis.__cdDb) globalThis.__cdDb.transaction('files', 'readwrite').objectStore('files').delete(name);
        else localStorage.removeItem('cd:' + name);
    } catch (e) {}
}""")

// ------------------------------------------------------------------ back button

/** Puts one entry on the browser history; the browser's back (or a swipe back) then calls cb. Returns a handle. */
internal fun jsBackListen(cb: () -> Unit): JsAny = js("""(function () {
    try { history.pushState({ cd: 1 }, ''); } catch (e) {}
    var h = { on: true };
    h.f = function () {
        if (!h.on) return;
        try { history.pushState({ cd: 1 }, ''); } catch (e) {}
        cb();
    };
    window.addEventListener('popstate', h.f);
    return h;
})()""")

/** Stops listening and takes the entry back off the history. */
internal fun jsBackStop(h: JsAny): Unit = js("""{
    h.on = false;
    window.removeEventListener('popstate', h.f);
    try { if (history.state && history.state.cd) history.back(); } catch (e) {}
}""")

// ------------------------------------------------------------------ share

internal fun jsShare(text: String): Unit = js("""{
    try {
        if (navigator.share) navigator.share({ text: text }).catch(function () {});
        else if (navigator.clipboard) navigator.clipboard.writeText(text).catch(function () {});
    } catch (e) {}
}""")

// ------------------------------------------------------------------ files

/**
 * Opens the file chooser for one picture; scales it to at most maxSide px on the long side and returns it as PNG bytes
 * (char codes 0..255), or null when cancelled or unreadable.
 */
internal fun jsPickImage(maxSide: Int, cb: (String?) -> Unit): Unit = js("""{
    var done = false;
    var finish = function (v) { if (!done) { done = true; cb(v); } };
    var input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.style.display = 'none';
    document.body.appendChild(input);
    input.addEventListener('cancel', function () { input.remove(); finish(null); });
    input.onchange = function () {
        var f = input.files && input.files[0];
        input.remove();
        if (!f) { finish(null); return; }
        createImageBitmap(f).then(function (bmp) {
            var s = Math.min(1, maxSide / Math.max(bmp.width, bmp.height));
            var w = Math.max(1, Math.round(bmp.width * s)), h = Math.max(1, Math.round(bmp.height * s));
            var c = document.createElement('canvas');
            c.width = w; c.height = h;
            c.getContext('2d').drawImage(bmp, 0, 0, w, h);
            c.toBlob(function (b) {
                if (!b) { finish(null); return; }
                b.arrayBuffer().then(function (ab) {
                    var u = new Uint8Array(ab), out = '';
                    for (var i = 0; i < u.length; i += 8192) out += String.fromCharCode.apply(null, u.subarray(i, i + 8192));
                    finish(out);
                }, function () { finish(null); });
            }, 'image/png');
        }, function () { finish(null); });
    };
    input.click();
}""")

// ------------------------------------------------------------------ camera scanner

/**
 * Full-screen camera view. About 12 times a second the picture (long side ≤ 640 px) goes to onFrame as luminance
 * (char codes 0..255, row by row) with its width, height and time (ms); onFrame returns the decoded code or null.
 * Plain QR codes are also read by the browser's own BarcodeDetector where there is one. onDone(code or null) once.
 */
internal fun jsScanOpen(onFrame: (String, Int, Int, Double) -> String?, onDone: (String?) -> Unit): Unit = js("""{
    var closed = false, stream = null, timer = 0, det = null, busy = false;
    var root = document.createElement('div');
    root.style.cssText = 'position:fixed;inset:0;z-index:9999;background:#000;display:flex;align-items:center;justify-content:center;overflow:hidden;';
    var video = document.createElement('video');
    video.setAttribute('playsinline', ''); video.muted = true; video.autoplay = true;
    video.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;object-fit:cover;';
    var box = document.createElement('div');
    box.style.cssText = 'position:relative;width:min(72vw,72vh);height:min(72vw,72vh);border:2px solid rgba(255,255,255,0.85);border-radius:14px;box-shadow:0 0 0 9999px rgba(0,0,0,0.45);';
    var hint = document.createElement('div');
    hint.textContent = 'point the camera at the code';
    hint.style.cssText = 'position:absolute;top:max(18px,env(safe-area-inset-top));left:0;right:0;text-align:center;color:#fff;font:600 15px system-ui,sans-serif;text-shadow:0 1px 3px #000;';
    var cancel = document.createElement('button');
    cancel.textContent = 'Cancel';
    cancel.style.cssText = 'position:absolute;bottom:max(26px,env(safe-area-inset-bottom));left:50%;transform:translateX(-50%);padding:12px 30px;border-radius:24px;border:0;background:#fff;color:#000;font:600 16px system-ui,sans-serif;';
    root.appendChild(video); root.appendChild(box); root.appendChild(hint); root.appendChild(cancel);
    document.body.appendChild(root);
    var canvas = document.createElement('canvas');
    var g = canvas.getContext('2d', { willReadFrequently: true });
    var close = function (code) {
        if (closed) return;
        closed = true;
        clearInterval(timer);
        try { if (stream) stream.getTracks().forEach(function (t) { t.stop(); }); } catch (e) {}
        root.remove();
        onDone(code);
    };
    cancel.onclick = function () { close(null); };
    try { if (window.BarcodeDetector) det = new BarcodeDetector({ formats: ['qr_code'] }); } catch (e) { det = null; }
    var n = 0;
    var tick = function () {
        if (closed || busy || video.readyState < 2 || !video.videoWidth) return;
        busy = true;
        try {
            var vw = video.videoWidth, vh = video.videoHeight;
            var s = Math.min(1, 640 / Math.max(vw, vh));
            var w = Math.max(1, Math.round(vw * s)), h = Math.max(1, Math.round(vh * s));
            if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
            g.drawImage(video, 0, 0, w, h);
            var px = g.getImageData(0, 0, w, h).data;
            var lum = new Uint8Array(w * h);
            for (var i = 0, j = 0; j < lum.length; i += 4, j++) lum[j] = (px[i] * 77 + px[i + 1] * 150 + px[i + 2] * 29) >> 8;
            var str = '';
            for (var k = 0; k < lum.length; k += 8192) str += String.fromCharCode.apply(null, lum.subarray(k, k + 8192));
            var code = onFrame(str, w, h, performance.now());
            if (code != null) { close(code); return; }
            n++;
            if (det && n % 3 === 0) det.detect(canvas).then(function (r) { if (r && r.length && r[0].rawValue) close(String(r[0].rawValue)); }, function () {});
        } catch (e) { console.log('SCAN frame failed: ' + e); }
        busy = false;
    };
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        hint.textContent = 'no camera here (it needs https and a browser with camera access)';
        return;
    }
    navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment', width: { ideal: 1280 }, height: { ideal: 720 } }, audio: false })
        .then(function (s) {
            if (closed) { s.getTracks().forEach(function (t) { t.stop(); }); return; }
            stream = s;
            video.srcObject = s;
            var p = video.play(); if (p && p.catch) p.catch(function () {});
            timer = setInterval(tick, 80);
        }, function (e) {
            hint.textContent = 'camera blocked: allow camera access for this site, or type the code instead';
        });
}""")

// ------------------------------------------------------------------ sound (Web Audio)

/**
 * Sound needs a tap first in a browser: the audio context is made (or resumed) on the first tap / key, then onReady.
 */
internal fun jsAudioArm(onReady: () -> Unit): Unit = js("""{
    var A = globalThis.__cdAudio = globalThis.__cdAudio || { bufs: [], music: null, musicId: -1, musicGain: null, front: true };
    var wake = function () {
        try {
            if (!A.ctx) {
                var C = window.AudioContext || window.webkitAudioContext;
                if (!C) return;
                A.ctx = new C();
            }
            if (A.ctx.state !== 'running' && A.front) {
                A.ctx.resume().then(function () { onReady(); }, function () {});
            } else if (!A.ready) { onReady(); }
            A.ready = true;
        } catch (e) {}
    };
    ['pointerdown', 'keydown', 'touchend'].forEach(function (ev) { window.addEventListener(ev, wake, { capture: true }); });
}""")

internal fun jsAudioRunning(): Boolean = js("!!(globalThis.__cdAudio && globalThis.__cdAudio.ctx && globalThis.__cdAudio.ctx.state === 'running')")

/** Stores sound [id]: samples as char codes 0..32767 (16-bit PCM shifted down one bit and up by 16384). */
internal fun jsAudioMake(id: Int, samples: String, rate: Int): Unit = js("""{
    var A = globalThis.__cdAudio;
    var n = Math.max(1, samples.length);
    var b = A.ctx.createBuffer(1, n, rate);
    var d = b.getChannelData(0);
    for (var k = 0; k < samples.length; k++) d[k] = (samples.charCodeAt(k) - 16384) / 16384;
    A.bufs[id] = b;
}""")

internal fun jsAudioForget(): Unit = js("""{
    var A = globalThis.__cdAudio; if (A) A.bufs = [];
}""")

internal fun jsAudioPlay(id: Int, gain: Double): Unit = js("""{
    var A = globalThis.__cdAudio;
    if (!A || !A.ctx || A.ctx.state !== 'running' || !A.bufs[id]) return;
    var src = A.ctx.createBufferSource();
    src.buffer = A.bufs[id];
    var g = A.ctx.createGain();
    g.gain.value = gain;
    src.connect(g); g.connect(A.ctx.destination);
    src.start();
}""")

/** Loops sound [id] as music (-1 stops it); the same id again only moves the level (a short fade). */
internal fun jsAudioMusic(id: Int, gain: Double): Unit = js("""{
    var A = globalThis.__cdAudio;
    if (!A || !A.ctx) return;
    if (id === A.musicId && A.music) {
        try { A.musicGain.gain.setTargetAtTime(gain, A.ctx.currentTime, 0.08); } catch (e) { A.musicGain.gain.value = gain; }
        return;
    }
    if (A.music) { try { A.music.stop(); } catch (e) {} A.music = null; }
    A.musicId = -1;
    if (id < 0 || !A.bufs[id]) return;
    var src = A.ctx.createBufferSource();
    src.buffer = A.bufs[id];
    src.loop = true;
    var g = A.ctx.createGain();
    g.gain.value = gain;
    src.connect(g); g.connect(A.ctx.destination);
    src.start();
    A.music = src; A.musicGain = g; A.musicId = id;
}""")

/** The tab went to the background (false) or came back (true): all sound pauses meanwhile. */
internal fun jsAudioFront(on: Boolean): Unit = js("""{
    var A = globalThis.__cdAudio;
    if (!A) return;
    A.front = on;
    if (!A.ctx) return;
    try { if (on) A.ctx.resume(); else A.ctx.suspend(); } catch (e) {}
}""")
