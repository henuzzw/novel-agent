# Novel Agent Web

Vue 3 + TypeScript frontend for the novel planning and writing workspace.

## Code organization

- `src/api/`: typed backend requests. `http.ts` shares headers, cache policy and error handling between JSON and text requests; JSON generation requests also update local activity state.
- `src/composables/`: shared Vue behavior. `useGlobalModelSettings` shares computed settings and pending reads across consumers, and protects successful saves from older read responses.
- `src/lib/`: reusable logic. `generation-activity.ts` merges local requests and server runs by stage in one pass, preserving request save failures until a later model run starts.
- `src/components/` and `src/views/`: workspace panels and route views.
- `src/__tests__/` and `e2e/`: unit and browser regression tests.

Server state uses Vue Query; shared local state uses Vue composables. Unused Pinia registration and dependency have been removed. API modules expose operations consumed by the current UI; unused legacy review, canon submission, project model settings and standalone checkpoint request wrappers have been removed. Backend compatibility endpoints are retained.

From this directory, run `npm run test:unit` for the lightweight unit suite and `npm run type-check` for TypeScript validation. Use `npm run test:unit:full` for all unit tests, or select affected files with `npx vitest run --config vitest.full.config.ts src/__tests__/<name>.spec.ts`.

## Prompt Settings

`/settings/prompts?template=MANUSCRIPT` opens the global prompt editor. The top bar and project settings link to it.
System instructions and optional stage guidance are persisted through `/api/v1/settings/prompts` with optimistic versions.
Import adaptation and continuation have separate templates. Project inputs and output schemas are not editable here.
Saving affects future model requests only; reset records a new version. Historical versions can be loaded as an unsaved draft.
Run `npx playwright test --config playwright.ux.config.ts prompt-settings.spec.ts` for mocked browser regression tests.
Backend restart/Flyway V049 is required. See `../../NOVEL_AGENT_PROMPT_MANAGEMENT.md`.

## Workspace Interaction

The workspace stores navigation in the URL, not in project data:

- `section`: main navigation (`writing`, `outline`, `materials`, `experience`, `chat`, `imports`, `runs`, `settings`).
- `planning`: planning view (`outline`, `style`).
- `writing`: chapter view (`manuscript`, `history`, `quality`, `opening`, `review`, `memory`).
- `chapter`: a positive chapter number.
- `materials`: materials view (`profiles`, `entities`, `timeline`, `foreshadows`, `relations`, `style`).

Example: `/projects/<projectId>?section=writing&chapter=2&writing=manuscript`.
Refresh and browser history restore the selected views. Invalid query values fall back safely.
Navigation never generates content. Unsaved bible, outline, manuscript, review and character edits prompt before leaving their work area; a browser refresh also warns about unsaved edits.

Generation controls use native radio groups; long lists such as historical versions remain selects.
Generation status starts compact, retaining running/failed stages and stop controls; expand it to see all stages.

Run the interaction regression tests with an installed Google Chrome:

```sh
npm run test:e2e:ux
```

The suite mocks backend/model requests and verifies navigation and edit protection. Screenshots and traces are retained on failure. It does not call a model or write project data.

## Recommended IDE Setup

[VS Code](https://code.visualstudio.com/) + [Vue (Official)](https://marketplace.visualstudio.com/items?itemName=Vue.volar) (and disable Vetur).

## Recommended Browser Setup

- Chromium-based browsers (Chrome, Edge, Brave, etc.):
  - [Vue.js devtools](https://chromewebstore.google.com/detail/vuejs-devtools/nhdogjmejiglipccpnnnanhbledajbpd)
  - [Turn on Custom Object Formatter in Chrome DevTools](http://bit.ly/object-formatters)
- Firefox:
  - [Vue.js devtools](https://addons.mozilla.org/en-US/firefox/addon/vue-js-devtools/)
  - [Turn on Custom Object Formatter in Firefox DevTools](https://fxdx.dev/firefox-devtools-custom-object-formatters/)

## Type Support for `.vue` Imports in TS

TypeScript cannot handle type information for `.vue` imports by default, so we replace the `tsc` CLI with `vue-tsc` for type checking. In editors, we need [Volar](https://marketplace.visualstudio.com/items?itemName=Vue.volar) to make the TypeScript language service aware of `.vue` types.

## Customize configuration

See [Vite Configuration Reference](https://vite.dev/config/).

## Project Setup

```sh
npm install
```

### Compile and Hot-Reload for Development

```sh
npm run dev
```

### Type-Check, Compile and Minify for Production

```sh
npm run build
```

### Run Unit Tests with [Vitest](https://vitest.dev/)

```sh
npm run test:unit
```

### Run End-to-End Tests with [Playwright](https://playwright.dev)

```sh
# Install browsers for the first run
npx playwright install

# When testing on CI, must build the project first
npm run build

# Runs the end-to-end tests
npm run test:e2e
# Runs the tests only on Chromium
npm run test:e2e -- --project=chromium
# Runs the tests of a specific file
npm run test:e2e -- tests/example.spec.ts
# Runs the tests in debug mode
npm run test:e2e -- --debug
```

### Lint with [ESLint](https://eslint.org/)

```sh
npm run lint
```
