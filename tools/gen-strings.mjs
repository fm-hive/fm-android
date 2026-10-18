// Generates Android string resources from the fm-app locale JSON files, so the
// full fm-app translation inventory (ru/en) is available to the Android client.
// Keys are prefixed with `fm_` and nested paths are joined by underscores.
//
// Usage: node tools/gen-strings.mjs
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

const SRC = { en: '../fm-app/src/i18n/locales/en.json', ru: '../fm-app/src/i18n/locales/ru.json' }
const DEST = {
  en: 'app/src/main/res/values/fm_strings.xml',
  ru: 'app/src/main/res/values-ru/fm_strings.xml',
}

function sanitize(name) {
  let out = name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_+|_+$/g, '')
  if (!out) out = 'string'
  if (!/^[a-z]/.test(out)) out = 's_' + out
  return 'fm_' + out
}

function flatten(value, prefix, out) {
  if (typeof value === 'string') {
    const name = sanitize(prefix)
    let unique = name
    let index = 2
    while (out[unique] !== undefined) unique = `${name}_${index++}`
    out[unique] = value
    return
  }
  if (value && typeof value === 'object') {
    for (const [key, child] of Object.entries(value)) {
      flatten(child, prefix ? `${prefix}_${key}` : key, out)
    }
  }
}

function escapeXml(text) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '\\&#39;')
}

const all = {}
for (const locale of Object.keys(SRC)) {
  all[locale] = {}
  flatten(JSON.parse(readFileSync(SRC[locale], 'utf8')), '', all[locale])
}
// fm-app locales may differ by a key: fall back to English everywhere so both
// resource files define the same names.
const union = new Set(Object.keys(all.en))
for (const key of Object.keys(all.ru)) union.add(key)
for (const locale of Object.keys(SRC)) {
  const strings = {}
  for (const key of union) {
    const value =
      all[locale][key] !== undefined
        ? all[locale][key]
        : all.en[key] !== undefined
          ? all.en[key]
          : all.ru[key]
    if (value === undefined) continue
    strings[key] = value
  }
  const lines = [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<!-- Generated from fm-app i18n by tools/gen-strings.mjs; do not edit. -->',
    '<resources>',
  ]
  for (const [name, value] of Object.entries(strings)) {
    lines.push(`    <string name="${name}">${escapeXml(value)}</string>`)
  }
  lines.push('</resources>')
  const output = DEST[locale]
  mkdirSync(dirname(output), { recursive: true })
  writeFileSync(output, lines.join('\n') + '\n')
  console.log(`wrote ${output}: ${Object.keys(strings).length} strings`)
}
