package io.beatmaps.common.dbo

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Index

object Collaboration : IntIdTable("collaboration", "collaborationId") {
    val mapId = reference("mapId", Beatmap)
    val collaboratorId = reference("collaboratorId", User)
    val accepted = bool("accepted")

    val link = Index(listOf(mapId, collaboratorId), true, "collaborationLink")
}

data class CollaborationDAO(val key: EntityID<Int>) : IntEntity(key) {
    companion object : IntEntityClass<CollaborationDAO>(Collaboration)
    val mapId by Collaboration.mapId
    val collaboratorId by Collaboration.collaboratorId
    val accepted by Collaboration.accepted
}
