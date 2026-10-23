import type { Comparison, Run } from './api';
import { separationLabels, type SeriesComparison } from './series';

/** Drafts follow docs/findings/TEMPLATE.md. They fill recorded facts only and never state a conclusion. */
const TODO = '<작성 필요>';
const cell = (v: unknown) =>
  v === null || v === undefined || v === '' ? '미측정' : String(v).replace(/\|/g, '\\|').replace(/\n/g, ' ');
const num = (v: number | null | undefined, digits = 2) =>
  v === null || v === undefined ? '미측정' : Number(v.toFixed(digits)).toString();
const today = () => new Date().toISOString().slice(0, 10);

function runFacts(label: string, run: Run) {
  const env = run.runMetadata.environment as Record<string, unknown>;
  const git = run.runMetadata.git as Record<string, unknown>;
  const p = run.runMetadata.parameters;
  return [
    `- ${label} Run ID: \`${run.runId}\` (${run.status}) · 대시보드 \`/app/runs/${run.runId}\``,
    `  - Git commit: \`${cell(git.commit)}\` · dirty: ${cell(git.dirty)}`,
    `  - JVM/OS: Java ${cell(env.javaVersion)} · ${cell(env.osName)} ${cell(env.osArch)} · CPU ${cell(env.availableProcessors)} · max heap ${cell(env.maxHeapBytes)} bytes`,
    `  - 정의 SHA-256: \`${cell(p['scenario.definition.sha256'])}\`${p['input.sha256'] ? ` · input SHA-256: \`${p['input.sha256']}\`` : ''}`,
  ];
}

export function runComparisonDraft(comparison: Comparison): string {
  const { baseline, candidate } = comparison;
  const differing = comparison.conditions.filter((c) => c.different);
  const hypothesis = candidate.runMetadata.parameters['scenario.hypothesis'] ?? baseline.runMetadata.parameters['scenario.hypothesis'];
  return [
    `# Finding: ${TODO}`,
    '',
    '상태: 초안',
    `작성자 및 날짜: ${TODO} · ${today()}`,
    '',
    '## Problem / Observed Fact',
    '',
    `${TODO} 실제 관측한 문제와 출처. 회상·추정과 직접 관측을 구분한다.`,
    '',
    '## Reconstructed Condition',
    '',
    `- Experiment ID: \`${baseline.experimentType}\``,
    `- 기록된 조건 ${comparison.conditions.length}개 중 다른 조건 ${differing.length}개:`,
    ...(differing.length
      ? differing.map((c) => `  - \`${c.field}\`: ${cell(JSON.stringify(c.baseline))} → ${cell(JSON.stringify(c.candidate))}`)
      : ['  - 없음']),
    `- ${TODO} 원래 환경과의 차이, 입력 생성 방법, 제외한 조건.`,
    '',
    '## Hypothesis / New Design',
    '',
    `- 기록된 가설: ${cell(hypothesis)}`,
    `- ${TODO} 변경한 변수, 기대 결과, 반증 기준.`,
    '',
    '## Evidence',
    '',
    ...runFacts('Baseline', baseline),
    ...runFacts('Candidate', candidate),
    `- 비교 화면: \`/app/compare?baseline=${baseline.runId}&candidate=${candidate.runId}\``,
    `- Export 보관 위치 / SHA-256 / 마지막 end 레코드와 metricCount 확인: ${TODO}`,
    '',
    'Run ID는 해당 `.lab` 저장소 안에서 추적한다. URL만으로 영구 보존되지 않으므로 export와 원본 artifact를 함께 보관한다.',
    '',
    '## Measured Result',
    '',
    '| Metric / 단위 | Baseline | Candidate | 반복 횟수 / 변동 | 증거 위치 |',
    '| --- | --- | --- | --- | --- |',
    ...comparison.values.map(
      (v) => `| ${cell(v.name)} (${cell(v.kind)}) / ${cell(v.unit)} | ${cell(v.baseline)} | ${cell(v.candidate)} | 각 1회 / 변동 미측정 | Run summary |`,
    ),
    '',
    '실제로 기록된 값만 적었다. 미측정값은 0으로 치환하지 않았다.',
    '',
    '## Interpretation',
    '',
    `${TODO} 관측으로 지지/반박되는 가설과 근거. 각 1회 실행이므로 일반적인 성능 우위로 주장하지 않는다.`,
    '',
    '## Limitations / Next Experiment',
    '',
    '- 각 조건이 SINGLE_PASS 1회다. 반복 측정(`/run-series`)으로 분포를 확인해야 한다.',
    ...comparison.cautions.map((c) => `- 서버 주의: ${c}`),
    `- ${TODO}`,
    '',
    '## Decision',
    '',
    `${TODO} 적용/보류할 결정과 범위.`,
    '',
  ].join('\n');
}

export function seriesComparisonDraft(comparison: SeriesComparison): string {
  const { baseline, candidate } = comparison;
  const series = (label: string, view: SeriesComparison['baseline']) => [
    `- ${label} 묶음: \`${view.series.seriesId}\` (${view.series.status}) · 시나리오 \`${view.series.scenarioId}\` · \`/app/series/${view.series.seriesId}\``,
    `  - 측정 ${view.series.repetitions}회 + warmup ${view.series.warmup}회, 성공한 측정 반복 ${view.measuredSucceeded}개`,
    `  - 정의 SHA-256: \`${view.series.definitionSha256}\``,
    `  - 측정 Run: ${view.members.filter((m) => !m.warmup).map((m) => `\`${m.runId}\``).join(', ') || '없음'}`,
  ];
  const range = (s: SeriesComparison['measures'][number]['baseline']) =>
    `${num(s.median)} (${num(s.min)}–${num(s.max)})`;
  return [
    `# Finding: ${TODO}`,
    '',
    '상태: 초안',
    `작성자 및 날짜: ${TODO} · ${today()}`,
    '',
    '## Problem / Observed Fact',
    '',
    `${TODO} 실제 관측한 문제와 출처.`,
    '',
    '## Reconstructed Condition',
    '',
    `- Experiment ID: \`${baseline.series.experimentType}\``,
    `- 두 묶음의 시나리오 정의: ${comparison.sameDefinition ? '같음(재현성 비교)' : '다름 — 파라미터 차이는 각 Run 상세에서 확인'}`,
    `- ${TODO} 원래 환경과의 차이, 입력 생성 방법, 제외한 조건.`,
    '',
    '## Hypothesis / New Design',
    '',
    `${TODO} 변경한 변수, 기대 결과, 반증 기준.`,
    '',
    '## Evidence',
    '',
    ...series('Baseline', baseline),
    ...series('Candidate', candidate),
    `- 비교 화면: \`/app/series/compare?baseline=${baseline.series.seriesId}&candidate=${candidate.series.seriesId}\``,
    `- Export 보관 위치 / SHA-256: ${TODO}`,
    '',
    '## Measured Result',
    '',
    '| Metric / 단위 | Baseline 중앙값 (min–max) | Candidate 중앙값 (min–max) | 반복 횟수 / 변동 | 증거 위치 |',
    '| --- | --- | --- | --- | --- |',
    ...comparison.measures.map(
      (m) =>
        `| ${cell(m.role)} \`${cell(m.name)}\` / ${cell(m.unit)} | ${range(m.baseline)} | ${range(m.candidate)} | n=${m.baseline.count}/${m.candidate.count} · CV ${m.baseline.coefficientOfVariation === null ? '미측정' : num(m.baseline.coefficientOfVariation * 100, 1) + '%'}/${m.candidate.coefficientOfVariation === null ? '미측정' : num(m.candidate.coefficientOfVariation * 100, 1) + '%'} · 중앙값 변화 ${m.medianChangePct === null ? '미측정' : num(m.medianChangePct, 1) + '%'} | 묶음 통계 |`,
    ),
    '',
    '판정(범위 겹침 기준, 통계적 유의성 검정 아님):',
    ...comparison.measures.map((m) => `- ${m.role}: ${separationLabels[m.separation]}`),
    '',
    '## Interpretation',
    '',
    `${TODO} 관측으로 지지/반박되는 가설과 근거. 범위가 겹치는 measure는 차이를 단정하지 않는다.`,
    '',
    '## Limitations / Next Experiment',
    '',
    '- 같은 기계·같은 프로세스에서 순서대로 실행한 반복이다. 다른 환경으로 일반화하지 않는다.',
    ...[baseline, candidate].flatMap((view, i) => {
      const label = i === 0 ? 'Baseline' : 'Candidate';
      const notes: string[] = [];
      const noisy = view.stability?.highVariationRoles ?? [];
      if (noisy.length) notes.push(`- ${label} 묶음은 변동 계수 10% 초과: ${noisy.join(', ')}`);
      const flaky = (view.checks ?? []).filter((c) => c.passed > 0 && c.failed > 0);
      if (flaky.length)
        notes.push(`- ${label} 묶음에서 판정이 달라진 CHECK: ${flaky.map((c) => `${c.checkId} ${c.passed}/${c.runs} 통과`).join(', ')}`);
      if (view.overlappingRunIds?.length) notes.push(`- ${label} 묶음 반복 중 다른 Run ${view.overlappingRunIds.length}개가 함께 실행됐다.`);
      return notes;
    }),
    `- ${TODO}`,
    '',
    '## Decision',
    '',
    `${TODO} 적용/보류할 결정과 범위.`,
    '',
  ].join('\n');
}
