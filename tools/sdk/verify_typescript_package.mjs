#!/usr/bin/env node

import { access, readFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const packageDirectory = path.resolve(process.argv[2] ?? 'sdks/typescript');
const packageJson = JSON.parse(
  await readFile(path.join(packageDirectory, 'package.json'), 'utf8')
);

for (const exported of Object.values(packageJson.exports)) {
  for (const target of Object.values(exported)) {
    await access(path.join(packageDirectory, target));
  }
}

const sdk = await import(pathToFileURL(path.join(packageDirectory, 'dist/index.js')));
if (typeof sdk.BrowserCloudClient !== 'function') {
  throw new Error('compatibility BrowserCloudClient is missing from the built package');
}
if (typeof sdk.BrowserCloudGeneratedClient !== 'function') {
  throw new Error('generated BrowserCloud client is missing from the built package');
}

const first = new sdk.BrowserCloudGeneratedClient({
  BASE: 'https://first.example',
  TOKEN: 'first-token',
});
const second = new sdk.BrowserCloudGeneratedClient({
  BASE: 'https://second.example',
  TOKEN: 'second-token',
});
if (first.request === second.request || first.session === second.session) {
  throw new Error('generated clients must not share mutable request or service instances');
}
for (const service of ['session', 'resource', 'proxy', 'groups', 'tags', 'enterprise']) {
  if (typeof first[service] !== 'object') {
    throw new Error(`generated client is missing service: ${service}`);
  }
}

console.log('typescript_sdk_package=true esm=true isolated_clients=true');
