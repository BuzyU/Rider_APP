const { PrismaClient } = require('@prisma/client')

let currentPrisma = new PrismaClient()

const proxy = new Proxy(currentPrisma, {
    get(target, prop, receiver) {
        if (prop === '$setInstance') {
            return (newInstance) => {
                currentPrisma = newInstance
            }
        }
        if (prop === '$getInstance') {
            return () => currentPrisma
        }
        const val = Reflect.get(currentPrisma, prop)
        if (typeof val === 'function') {
            return val.bind(currentPrisma)
        }
        return val
    },
    set(target, prop, value) {
        return Reflect.set(currentPrisma, prop, value)
    }
})

module.exports = proxy

