/**
 * 后端 DTO / 枚举 与前端手写类型的漂移检查。
 *
 * 前端 types/index.ts 是手抄后端 DTO 的（没有代码生成），字段改名/新增时两边会悄悄不一致，
 * 而且前端没有测试框架兜不住。这个脚本按「字段名集合 / 枚举常量集合」比对，发现漂移即退出非 0。
 *
 * 用法：npm run check:types
 * 注意：只比名字，不比类型（String vs number 这类差异不在此检查范围）。
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '../..');

const COMMON = 'sdncustom-common/src/main/java/com/sdncustom/common';

const INTERFACES = [
  { java: `${COMMON}/model/Channel.java`, ts: 'Channel' },
  // channelId/address 现在是持久化字段，参与序列化
  { java: `${COMMON}/model/MeasurementPoint.java`, ts: 'MeasurementPoint' },
  { java: `${COMMON}/model/PointValue.java`, ts: 'PointValue' },
  { java: `${COMMON}/model/BusinessSystem.java`, ts: 'BusinessSystem' },
  { java: `${COMMON}/dto/SystemStatusDTO.java`, ts: 'SystemStatus' },
];

const ENUMS = [
  { java: `${COMMON}/model/enums/ProtocolType.java`, ts: 'ProtocolType' },
  { java: `${COMMON}/model/enums/ChannelDirection.java`, ts: 'ChannelDirection' },
  { java: `${COMMON}/model/enums/ChannelStatus.java`, ts: 'ChannelStatus' },
  { java: `${COMMON}/model/enums/PointDataType.java`, ts: 'PointDataType' },
  { java: `${COMMON}/model/enums/PointQuality.java`, ts: 'PointQuality' },
  { java: `${COMMON}/model/enums/PointDirection.java`, ts: 'PointDirection' },
];

const read = (rel) => readFileSync(resolve(repoRoot, rel), 'utf8');

/**
 * 提取 Java 类参与 JSON 序列化的私有字段名。
 * 只有 @JsonIgnore 才排除——@Transient 是 JPA 语义（不进表），Jackson 照样序列化。
 */
function javaFields(source) {
  const fields = [];
  let pendingAnnotations = [];
  for (const rawLine of source.split('\n')) {
    const line = rawLine.trim();
    if (line.startsWith('@')) {
      pendingAnnotations.push(line);
      continue;
    }
    const field = /^private\s+[\w.<>,\s[\]]+?\s+([a-zA-Z_]\w*)\s*(?:=.*)?;$/.exec(line);
    if (field) {
      const ignored = pendingAnnotations.some((a) => /^@JsonIgnore\b/.test(a));
      if (!ignored) fields.push(field[1]);
      pendingAnnotations = [];
      continue;
    }
    if (line) pendingAnnotations = [];
  }
  return fields;
}

/** 提取 Java 枚举常量名 */
function javaEnumConstants(source) {
  const body = source.slice(source.indexOf('{') + 1, source.lastIndexOf('}'));
  return body
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('//') && !l.startsWith('*') && !l.startsWith('/*'))
    .flatMap((l) => l.split(','))
    .map((l) => /^([A-Z][A-Z0-9_]*)\s*(?:\(|;|$)/.exec(l.replace(/;$/, '')))
    .filter(Boolean)
    .map((m) => m[1]);
}

/** 提取 TS interface 的属性名 */
function tsInterfaceProps(source, name) {
  const m = new RegExp(`export\\s+interface\\s+${name}\\s*\\{([\\s\\S]*?)\\n\\}`).exec(source);
  if (!m) return null;
  return [...m[1].matchAll(/^\s*([a-zA-Z_]\w*)\??\s*:/gm)].map((x) => x[1]);
}

/** 提取 TS 联合类型的字符串字面量 */
function tsUnionMembers(source, name) {
  const m = new RegExp(`export\\s+type\\s+${name}\\s*=\\s*([^;]+);`).exec(source);
  if (!m) return null;
  return [...m[1].matchAll(/'([^']+)'/g)].map((x) => x[1]);
}

const tsSource = read('sdncustom-web/src/types/index.ts');
const problems = [];

function compare(label, javaNames, tsNames, only) {
  if (tsNames === null) {
    problems.push(`${label}: 前端类型里找不到对应声明`);
    return;
  }
  const expected = only ? javaNames.filter((n) => only.includes(n)) : javaNames;
  const missing = expected.filter((n) => !tsNames.includes(n));
  const extra = tsNames.filter((n) => !expected.includes(n));
  if (missing.length) problems.push(`${label}: 前端缺少字段/常量 ${missing.join(', ')}`);
  if (extra.length) problems.push(`${label}: 前端多出字段/常量 ${extra.join(', ')}（后端已无？）`);
  if (!missing.length && !extra.length) {
    console.log(`  OK   ${label} (${expected.length})`);
  }
}

console.log('后端 DTO ↔ 前端类型 漂移检查');
for (const { java, ts, only } of INTERFACES) {
  compare(`${java.split('/').pop()} ↔ ${ts}`, javaFields(read(java)), tsInterfaceProps(tsSource, ts), only);
}
for (const { java, ts } of ENUMS) {
  compare(`${java.split('/').pop()} ↔ ${ts}`, javaEnumConstants(read(java)), tsUnionMembers(tsSource, ts));
}

if (problems.length) {
  console.error('\n发现漂移:');
  problems.forEach((p) => console.error(`  - ${p}`));
  process.exit(1);
}
console.log('\n没有漂移。');
