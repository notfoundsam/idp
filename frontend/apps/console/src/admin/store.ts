import { defineStore } from 'pinia'

/** What an admin is told on the page a change leads to, such as the users after an erasure. The page takes it once. */
export const useAdminStore = defineStore('admin', {
  state: () => ({
    notice: null as string | null,
  }),
  actions: {
    take(): string | null {
      const notice = this.notice
      this.notice = null
      return notice
    },
  },
})
