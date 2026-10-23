# La Fosa y Chispas — matriz manual (Bloque 3)

Prueba en servidor Paper y en cliente: **PENDIENTE** (no ejecutada en esta tarea).

Contrato vigente: vuelta N cuesta N Chispas (1, 2, 3); tope 6 ganadas por UUID
por temporada; máximo 3 vueltas; `revivals.<uuid>` en `season-state.yml` manda,
`revived.<uuid>` en `dead-players.yml` es historial.

| # | Caso | Pasos | Esperado |
|---|------|-------|----------|
| 1 | Run completa | Morir en temporada → reentrar (login permitido) → `/pd fosa empezar` → 8 bajas antes del tiempo | +1 Chispa, mensaje privado, sin broadcast global |
| 2 | Tope diario | Completar 2 runs el mismo día real | 2ª sin Chispa (“ya ganaste hoy”) |
| 3 | Tope temporada | 6 ganadas acumuladas → otra run | Sin Chispa (“cupo agotado”); gastar no reabre cupo |
| 4 | Ritual ≥3 vivos | 2 vivos + Sello sobre la cabeza, N Chispas | Vuelta N/3, Chispas consumidas, inventario/ender limpio 1 vez |
| 5 | Ritual 2 vivos | 1 vivo + Sello | Vuelta con mínimo 1 participante |
| 6 | Altar ≤1 vivo | Sin vivos del roster, 1 Núcleo en banco + N Chispas, `/pd fosa volver` | Vuelta solitaria con peaje |
| 7 | Válvula 5 días | Muerto 5 días reales con Chispas ≥ N, haya vivos o no, `/pd fosa volver` | Altar solitario con 1 Núcleo, sin Sello |
| 8 | 4ª vuelta | 3 vueltas consumidas, aunque haya Chispas/núcleos | Bloqueada (“3 vueltas agotadas”) |
| 9 | Último vivo | Muere el último del roster → juega Fosa → ritual/altar | Puede volver solo con su coste |
| 10 | Fin de temporada | 0 vivos y nadie online | Señal a B4 en log, sin finalizar entre muerte y kick |
| 11 | Abandono/desconexión | Salir a mitad de run o quedarse AFK 3 min | Fallo controlado, sin Chispa, mobs limpiados |
| 12 | Restart | Reiniciar a mitad de run o de ritual | Sin Chispa, sin vuelta consumida, sin duplicar |
| 13 | Forzado | `/pd revivirforzado <jugador>` (admin) | Vuelve sin coste ni contador, log en consola, historial intacto |
| 14 | Normal por comando | `/pd revivir <jugador>` en temporada | Pasa por policy (consume Chispas/contador); Ancla solo se gasta en éxito |
| 15 | Aislamiento | En run: cofres, drops, PvP, portales, otros comandos, salir del recinto | Todo bloqueado con aviso privado; recinto contenido en Reserva |
| 16 | Duración | `season.pit-duration-minutes` 9 o 20 | Se usa 12 (clamp 10–15, sin destruir datos) |
