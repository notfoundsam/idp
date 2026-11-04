<script setup lang="ts">
import type { AuditEntry, AuditTargetType } from '@idp/api-client'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ProblemMessage from '../../components/ProblemMessage.vue'
import { dateOf } from '../../messages'
import { actorOf, hasStatus, journalOf, TARGET_TYPES, targetOf, targetTypeOf, useJournal } from '../admin'

const route = useRoute()
const router = useRouter()
const target = computed(() => targetOf(route.query.targetType, route.query.targetId))
const journal = useJournal(target)
const entries = computed(() => journal.data.value?.pages.flatMap((page) => page.entries) ?? [])

const wantedType = ref<AuditTargetType>('USER')
const wantedId = ref('')

watch(
  target,
  (current) => {
    if (current === undefined) return
    wantedType.value = current.type
    wantedId.value = current.id
  },
  { immediate: true },
)

async function filter() {
  await router.push(journalOf({ type: wantedType.value, id: wantedId.value.trim() }))
}

const detailsOf = (entry: AuditEntry) => Object.entries(entry.details ?? {}).map(([key, value]) => `${key}: ${value}`)
</script>

<template>
  <h1>Journal</h1>
  <p>What happened in your organisation, the newest first, with the address of whoever acted.</p>

  <form data-testid="target-form" @submit.prevent="filter">
    <label for="target-type">Target</label>
    <select id="target-type" v-model="wantedType" name="target-type">
      <option v-for="option in TARGET_TYPES" :key="option" :value="option">{{ targetTypeOf(option) }}</option>
    </select>
    <label for="target-id">ID of the target</label>
    <input id="target-id" v-model="wantedId" name="target-id" type="text" autocomplete="off" required />
    <button type="submit">Show only this target</button>
  </form>
  <p v-if="target" data-testid="audit-filter">
    Only the records about {{ targetTypeOf(target.type).toLowerCase() }} <code>{{ target.id }}</code>.
    <RouterLink :to="{ name: 'audit' }">Show every record</RouterLink>
  </p>

  <p v-if="hasStatus(journal.error.value, 400)" role="alert" data-testid="audit-refused">That is not the ID of a target.</p>
  <ProblemMessage v-else :error="journal.error.value" />
  <p v-if="journal.isPending.value && !journal.error.value" role="status">Loading…</p>
  <template v-if="journal.data.value">
    <table v-if="entries.length > 0">
      <thead>
        <tr>
          <th scope="col">Time</th>
          <th scope="col">Action</th>
          <th scope="col">By</th>
          <th scope="col">Target</th>
          <th scope="col">Address</th>
          <th scope="col">Details</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="entry in entries" :key="entry.id" data-testid="audit-entry">
          <td>{{ dateOf(entry.occurredAt) }}</td>
          <td><code data-testid="audit-action">{{ entry.action }}</code></td>
          <td>
            <RouterLink v-if="entry.actorType === 'USER' && entry.actorId" :to="{ name: 'user', params: { id: entry.actorId } }">
              {{ actorOf(entry) }}
            </RouterLink>
            <template v-else>{{ actorOf(entry) }}</template>
          </td>
          <td>
            <template v-if="entry.targetType && entry.targetId">
              {{ targetTypeOf(entry.targetType) }}
              <RouterLink :to="journalOf({ type: entry.targetType, id: entry.targetId })" data-testid="audit-target">
                <code>{{ entry.targetId }}</code>
              </RouterLink>
            </template>
            <template v-else>None</template>
          </td>
          <template v-if="entry.details === null">
            <td colspan="2" data-testid="audit-erased">Removed with the data of the user who acted</td>
          </template>
          <template v-else>
            <td data-testid="audit-ip">{{ entry.ip ?? 'None' }}</td>
            <td data-testid="audit-details">
              <ul v-if="detailsOf(entry).length > 0">
                <li v-for="detail in detailsOf(entry)" :key="detail">{{ detail }}</li>
              </ul>
              <template v-else>None</template>
            </td>
          </template>
        </tr>
      </tbody>
    </table>
    <p v-else data-testid="no-audit-entries">No records.</p>
    <button v-if="journal.hasNextPage.value" type="button" :disabled="journal.isFetchingNextPage.value" @click="journal.fetchNextPage()">
      Show older records
    </button>
    <p v-else-if="entries.length > 0" data-testid="audit-end">These are all the records.</p>
  </template>
</template>
