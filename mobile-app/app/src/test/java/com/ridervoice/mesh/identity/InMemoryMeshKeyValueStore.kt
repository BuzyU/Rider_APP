package com.ridervoice.mesh.identity

class InMemoryMeshKeyValueStore : MeshKeyValueStore {
    private val map = HashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
}

