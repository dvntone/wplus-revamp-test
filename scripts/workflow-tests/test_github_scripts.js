const fs = require('fs'); const assert = require('assert');
const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
function mock({ runName, prs = [], existingComments = 0, withMarker = null }) {
  const calls = []; const comments = [];
  for (let i = 0; i < existingComments; i++) comments.push({ id: i, user: { type: 'User' }, body: 'x' });
  if (withMarker) comments.push({ id: 999, user: { type: 'Bot' }, body: withMarker });
  const rest = {
    actions: {
      getWorkflowRun: async () => ({ data: { name: runName, conclusion: 'failure', head_branch: 'feat', head_sha: 'abc', run_number: 7, html_url: 'u', pull_requests: prs } }),
      listJobsForWorkflowRun: async () => ({ data: { jobs: [{ name: 'Build', conclusion: 'failure', steps: [{ name: 'Run unit tests', conclusion: 'failure' }] }] } }),
    },
    pulls: { list: async () => ({ data: [] }) },
    issues: {
      listComments: 'LIST',
      createComment: async a => calls.push(['create', a]),
      updateComment: async a => calls.push(['update', a]),
      listForRepo: async () => ({ data: [] }),
      getLabel: async () => ({}), createLabel: async () => {},
      create: async a => { calls.push(['issue', a]); return { data: { number: 5 } }; },
    },
  };
  const github = { rest, paginate: async (fn, a) => { assert.strictEqual(fn, 'LIST'); calls.push(['paginate', a]); return comments; } };
  const context = { repo: { owner: 'o', repo: 'r' }, issue: { number: 1 }, payload: { workflow_run: { id: 42 } } };
  const core = { info() {}, setOutput() {} };
  return { github, context, core, calls };
}
async function run(file, m) { await new AsyncFunction('github', 'context', 'core', 'require', fs.readFileSync(file, 'utf8'))(m.github, m.context, m.core, require); return m.calls; }
const S = process.argv[2]; /* dir with extracted notify.js + summary.js */ let n = 0; const ok = s => { n++; console.log('ok  ', s); };
(async () => {
  // ── notify-waiting ──
  const env = (o) => Object.assign(process.env, { SONAR_TOKEN_PRESENT: 'false', SONAR_ORG_PRESENT: 'false', KEYSTORE_PRESENT: 'false', MANUAL_RUN_ID: '' }, o);
  env({ SONAR_TOKEN_PRESENT: 'true', SONAR_ORG_PRESENT: 'true', KEYSTORE_PRESENT: 'true' });
  let c = await run(`${S}/notify.js`, mock({ runName: 'Code Quality Gate', prs: [{ number: 3 }] }));
  let body = c.find(x => x[0] === 'create')[1].body;
  assert(!body.includes('SONAR_TOKEN') && !body.includes('KEYSTORE')); assert(body.includes('Code fix required')); ok('notify: Code Quality failure with secrets set → no false secret blame');
  env({}); c = await run(`${S}/notify.js`, mock({ runName: 'Code Quality Gate', prs: [{ number: 3 }] }));
  assert(!c.find(x => x[0] === 'create')[1].body.includes('SONAR_TOKEN')); ok('notify: Code Quality never blames Sonar (old bug)');
  env({}); c = await run(`${S}/notify.js`, mock({ runName: 'SonarCloud Analysis', prs: [{ number: 3 }] }));
  body = c.find(x => x[0] === 'create')[1].body; assert(body.includes('SONAR_TOKEN') && body.includes('SONAR_ORGANIZATION')); ok('notify: SonarCloud with nothing set → asks for token + org');
  env({ SONAR_TOKEN_PRESENT: 'true' }); c = await run(`${S}/notify.js`, mock({ runName: 'SonarCloud Analysis', prs: [{ number: 3 }] }));
  body = c.find(x => x[0] === 'create')[1].body; assert(!body.includes('`SONAR_TOKEN`') && body.includes('SONAR_ORGANIZATION')); ok('notify: SonarCloud token set, org missing → asks for org only');
  env({}); c = await run(`${S}/notify.js`, mock({ runName: 'Android Release & Deploy', prs: [{ number: 3 }] }));
  body = c.find(x => x[0] === 'create')[1].body; assert(body.includes('KEYSTORE_BASE64') && body.includes('REQUIRED_SETUP.md` §1.1')); ok('notify: Release without keystore → optional signing notice, valid doc path');
  env({ KEYSTORE_PRESENT: 'true' }); c = await run(`${S}/notify.js`, mock({ runName: 'Android Release & Deploy', prs: [{ number: 3 }] }));
  assert(!c.find(x => x[0] === 'create')[1].body.includes('KEYSTORE_BASE64')); ok('notify: Release with keystore → no signing notice');
  env({}); c = await run(`${S}/notify.js`, mock({ runName: 'Android CI', prs: [{ number: 3 }], existingComments: 150, withMarker: '<!-- wscanplus-needs-attention -->' }));
  assert(c.some(x => x[0] === 'update' && x[1].comment_id === 999)); ok('notify: marker beyond page 1 is found and updated (paginate)');
  env({}); c = await run(`${S}/notify.js`, mock({ runName: 'Android CI', prs: [] }));
  assert(c.some(x => x[0] === 'issue')); ok('notify: no PR → opens ci-attention issue');
  env({ MANUAL_RUN_ID: "1'); process.exit(9); ('" }); const m = mock({ runName: 'Android CI', prs: [{ number: 3 }] });
  await run(`${S}/notify.js`, m); ok('notify: hostile run_id input is data, not code');
  // ── code-quality post-summary ──
  Object.assign(process.env, { ANDROID_RESULT: 'failure', HEAD_SHA: 'deadbeef', HEAD_REF: 'x`[click](https://evil)`' });
  c = await run(`${S}/summary.js`, mock({ existingComments: 120, withMarker: '## 🛡 wscan+ Code Quality Gate old' }));
  const up = c.find(x => x[0] === 'update'); assert(up && up[1].comment_id === 999); ok('summary: updates existing marker comment past page 1');
  assert(up[1].body.includes('❌') && up[1].body.includes('`x[click](https://evil)`') && !/`x`/.test(up[1].body)); ok('summary: failure shown ❌; backticks stripped from ref');
  process.env.ANDROID_RESULT = 'success'; c = await run(`${S}/summary.js`, mock({}));
  assert(c.find(x => x[0] === 'create')[1].body.includes('All checks passed')); ok('summary: success → new comment created');
  console.log(`== ${n} passed`);
})().catch(e => { console.error('FAIL', e); process.exit(1); });
