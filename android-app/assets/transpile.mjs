// DSH 前端本地转译器：把 dsh-web-frontend/dist/assets 转译到指定目标语法。
// 用法: node transpile.mjs <es2015|es2017|es2020> [frontDist] [outDir]
import { createRequire } from 'node:module';
const req = createRequire(process.cwd() + '/');
let transform;
let esbuildBuild;
try {
  ({ transform, build: esbuildBuild } = req('esbuild'));
} catch (e) {
  const esbDir = process.env.DSH_ESBUILD_DIR || '/root/esb';
  ({ transform, build: esbuildBuild } = createRequire(esbDir + '/index.js')('esbuild'));
}
import { readdir, mkdir, readFile, writeFile, copyFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';

const target = process.argv[2] || 'es2017';
const frontDist = process.argv[3] || '/usr/local/node-v24.21.0-linux-arm64/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist';
const outDir = process.argv[4] || '/storage/emulated/0/DEH/webpatch';

async function walk(dir, base) {
  const entries = await readdir(dir, { withFileTypes: true });
  const files = [];
  for (const e of entries) {
    const rel = base ? path.join(base, e.name) : e.name;
    const abs = path.join(dir, e.name);
    if (e.isDirectory()) files.push(...await walk(abs, rel));
    else files.push({ abs, rel });
  }
  return files;
}

async function main() {
  const assets = path.join(frontDist, 'assets');
  if (!existsSync(assets)) {
    console.error('assets not found: ' + assets);
    process.exit(2);
  }
  const files = await walk(assets, '');
  let ok = 0, fail = 0;
  for (const f of files) {
    const dst = path.join(outDir, 'assets', f.rel);
    await mkdir(path.dirname(dst), { recursive: true });
    if (f.rel.endsWith('.js')) {
      try {
        const code = await readFile(f.abs, 'utf8');
        const result = await transform(code, { loader: 'js', target, minify: true });
        await writeFile(dst, result.code);
        ok++;
      } catch (err) {
        console.error('FAIL ' + f.rel + ': ' + err.message);
        fail++;
      }
    } else {
      await copyFile(f.abs, dst);
    }
  }
  // 插件白名单：只转译需要兼容旧 WebView 的功能插件（如上传）
  const WHITELIST = ['dsh-client-file-upload'];
  const pluginBase = path.resolve(frontDist, '../../..', '@deepseek-ai');
  let pok = 0, pfail = 0;
  try {
    const pkgs = await readdir(pluginBase, { withFileTypes: true });
    for (const pkg of pkgs) {
      if (!pkg.isDirectory() || !WHITELIST.includes(pkg.name)) continue;
      const libDir = path.join(pluginBase, pkg.name, 'lib');
      if (!existsSync(libDir)) continue;
      const files = await walk(libDir, '');
      for (const f of files) {
        if (!f.rel.endsWith('.js')) continue;
        if (f.rel === 'index.js' || f.rel.endsWith('/index.js')) continue;
        try {
          const code = await readFile(f.abs, 'utf8');
          const result = await transform(code, { loader: 'js', target, minify: true });
          const dst = path.join(outDir, 'plugins', pkg.name, 'lib', f.rel);
          await mkdir(path.dirname(dst), { recursive: true });
          await writeFile(dst, result.code);
          pok++;
        } catch (err) {
          pfail++;
        }
      }
    }
  } catch (e) {
    console.error('plugin whitelist failed: ' + e.message);
  }
  console.log('whitelist plugins transpiled=' + pok + ' failed=' + pfail);
  // 插件不转译：跟随 DSH 版本（旧 WebView 用旧版 DSH）

  for (const extra of ['index.html', 'manifest.webmanifest', 'favicon.svg', 'favicon-dark.svg']) {
    const src = path.join(frontDist, extra);
    if (existsSync(src)) await copyFile(src, path.join(outDir, extra));
  }
  console.log('transpiled=' + ok + ' failed=' + fail + ' target=' + target + ' out=' + outDir);
  process.exit(fail > 0 ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(2); });
