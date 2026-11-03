import js from '@eslint/js'
import vue from 'eslint-plugin-vue'
import { defineConfig, globalIgnores } from 'eslint/config'
import globals from 'globals'
import tseslint from 'typescript-eslint'

const MAP_INSTEAD = 'Keep data with keys from outside in a Map or a Set, a plain object lets a key such as __proto__ reach its prototype.'
const NO_MESSAGES = 'The pages take no messages from other windows, see V3.5.5.'
const NO_HTML = 'Text goes into the DOM as text, see V3.2.2.'

export default defineConfig(
  globalIgnores(['**/dist/', '**/generated/', '**/node_modules/', 'e2e/test-results/', 'e2e/playwright-report/', 'e2e/fixtures/']),
  js.configs.recommended,
  tseslint.configs.strict,
  vue.configs['flat/essential'],
  {
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
  },
  {
    files: ['**/*.vue'],
    languageOptions: { parserOptions: { parser: tseslint.parser } },
  },
  {
    rules: {
      eqeqeq: ['error', 'always'],
      'no-eval': 'error',
      'no-implied-eval': 'error',
      'no-new-func': 'error',
      'no-script-url': 'error',
      'vue/no-v-html': 'error',
      'no-restricted-syntax': [
        'error',
        { selector: "CallExpression[callee.property.name='addEventListener'][arguments.0.value='message']", message: NO_MESSAGES },
        { selector: "AssignmentExpression[left.property.name='onmessage']", message: NO_MESSAGES },
        { selector: 'AssignmentExpression[left.property.name=/^(inner|outer)HTML$/]', message: NO_HTML },
        { selector: "CallExpression[callee.property.name='insertAdjacentHTML']", message: NO_HTML },
        { selector: "CallExpression[callee.object.name='document'][callee.property.name=/^write(ln)?$/]", message: NO_HTML },
        { selector: 'TSIndexSignature', message: MAP_INSTEAD },
        { selector: "TSTypeReference[typeName.name='Record']", message: MAP_INSTEAD },
      ],
    },
  },
)
