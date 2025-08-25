package com.tuduticket.qrm

/**
 * Données d'un événement à générer.
 *
 * @property name     Nom de l’événement
 * @property date     Date au format YYYY‑MM‑DD
 * @property start    Heure de début
 * @property end      Heure de fin
 * @property place    Lieu de l’événement
 * @property quantity Nombre de QR codes à générer
 */
data class EventData(
    val name: String,
    val date: String,
    val start: String,
    val end: String,
    val place: String,
    val quantity: Int
) {
    /**
     * Map de base (sans ref) pour insertion dans Firestore.
     */
    fun toMapBase() = mapOf(
        "name"      to name,
        "date"      to date,
        "start"     to start,
        "end"       to end,
        "place"     to place,
        "timestamp" to System.currentTimeMillis()
    )
}
