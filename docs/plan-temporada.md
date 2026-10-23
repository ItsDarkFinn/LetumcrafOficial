# Plan de temporada — LetumcrafOficial

Estado: documento de planificación (solo documentación). No implementa código.
Las **enmiendas confirmadas** por el usuario al final de este documento son la versión vigente: prevalecen sobre cualquier valor anterior que aparezca marcado como sustituido.

## Contexto técnico

Raíz `C:\pluggin dark`. Paper API `26.3.build.206-stable`, Java 25, Gradle 9.2.1. JDK 25 ya instalado en `C:\Users\cerez\AppData\Local\LetumcraftTools\jdk-25.0.4.1+1`. Build con `JAVA_HOME` apuntando a ese JDK: `gradlew.bat test build`.

Salidas: `build/libs/PermadeathEscalating-0.2.45.jar` y `build/dist/Letumcraft-ResourcePack-0.2.45.zip`.

No cambiar toolchain ni `api-version`, no instalar nada más. El recuento de tests del borrador original está anticuado: tomar la línea base real al ejecutar, nunca inventar resultados.

## Reglas cerradas

1. Roster cerrado, nadie nuevo a mitad de temporada.
2. Temporada de 30–45 días reales, default 40.
3. Un sacrificio por jugador, irreversible.
4. La Fosa es obligatoria para revivir; se paga en Chispas.
5. **Vueltas escaladas (vigente)**: hasta **3 vueltas por jugador** por temporada. La vuelta *N* cuesta *N* Chispas (1, 2, 3). Tope **6 Chispas ganadas por UUID por temporada**. Ledger propio `revivals.<uuid>` por temporada; `revived.<uuid>` en `DeathRegistry` queda como historial, **no como bloqueo**. Sustituye la regla antigua de una sola vuelta y los precios 1/2/4.
6. Precio de volver según estado del roster, **no** según conectados. El **coste en Chispas** depende del número de vuelta; los **requisitos de ritual** dependen del roster.
7. Final con fecha: el día 40 el sol se apaga o se enciende.

## Reutilizar

`DayManager`. `PermadeathPlugin.isPhaseActive(int)` como único punto de decisión de efectos de fase. Fases 10…100 de `PhaseTasks` y `DifficultyListener`: no reescribirlas, recomprimir umbrales. `phaseBar`, `DeathRegistry` (dead/revived UUID), `ResurrectionRitual` para caminos 1/2, `LootInventory.addRandomly`, patrón `EclipseCoreItem` Material+ItemMeta+setItemModel+marca BYTE PDC.

## Bloque 1 — Temporada y actos

1.1 `SeasonCalendar` puro sin Bukkit: `phaseDay(fase) = max(1, round(fase * seasonLengthDays / 100))`; `isPhaseUnlocked(fase, día) = día >= phaseDay(fase)`.
Con 40: 10→4, 20→8, 25→10, 35→14, 40→16, 50→20, 60→24, 70→28, 80→32, 90→36, 100→40.
Con 30: 3, 6, 8, 11, 12, 15, 18, 21, 24, 27, 30.
Con 100 reproducir exactamente los umbrales actuales, por compatibilidad y tests. No renombrar claves `phases.<fase>.enabled`.

1.2 Actos con 40: I 1–3 La Grieta; II 4–13 El Insomnio; III 14–15 El Eclipse; IV 16–23 La Invasión; V 24–31 La Hambruna; VI 32–39 La Soledad; VII 40 El Último Día.
Bossbar: `Letumcraft · Acto III — El Eclipse · Día 14 · Vivos 9/16`. Anuncio único de acto persistido.

1.3 M = rosterSize de config (default 16). N = roster menos eliminados `DeathRegistry`, **NO** conectados. Lista explícita `season.roster: [UUID...]` es fuente de verdad cuando existe; M = tamaño real de esa lista. Si falta la lista, usar fallback numérico con advertencia visible y no inventar UUID ni autorrellenar roster con nuevos jugadores. Implementar enforcement del roster cerrado configurable, sin reabrir cupos al morir/revivir.

1.4 Si N < 4, efectos de fase suspendidos; bossbar/calendario siguen. Al volver a 4, reanudar. No confundir desbloqueo narrativo con efectos suspendidos.

1.5 Gates: `requeridos = max(2, ceil(M * gatePercent / 100))`, `gatePercent` 40 default. Aplicar automáticos `NightHuntEvent`, `NetherAshstormEvent`, `FortressHuntEvent`, `CaravanEvent`. Si `minimum-online-players` configurado es mayor, prevalece. Contar online elegibles del roster para el gate online, nunca para precio/vivos totales; mantener bypass de pruebas manuales de operador. Documentar precedencia incluso si el default heredado 15 domina el resultado 7 para 16 jugadores.

1.6 Objetivos: una meta por acto, anuncio único, cumplimiento único y recompensa pequeña configurable. Evaluar por eventos, nunca escanear periódicamente entidades/mundos. Si la recompensa depende de bloques 2/3 inexistentes, degradar a mensaje sin excepción. Metas originales: I abrir portal Reserva; II noche sin dormir + muestra esporas; III primer Núcleo; IV completar Fortaleza; V completar Cacería; VI 3 días con <5 vivos; VII fondo banco antes del último día. Objetivos abiertos persisten entre actos sin caducar: el objetivo I (portal) no es físicamente posible en días 1–3 porque se genera al 8; no adelantar el portal para disimularlo. Cualquier cambio de esas metas requiere aprobación: no sustituirlas por ideas del redactor sin pedir permiso.

1.7 `/pd temporada` muestra acto, día, próximo acto, vivos N/M, objetivo. `/pd actos` muestra calendario.

1.8 `SeasonCalendarTest`: días 30/40, monotonía estricta en fases soportadas, fase 100 al final, compatibilidad 100. `ObjectivesTest`: anunciar/completar/pagar 1 vez y degradación segura.

DoD: build verde; `/pd dia 8` desbloquea fase 20 con 40 días; bossbar acto/vivos; suspensión visible con 3 vivos. Pruebas Paper no ejecutadas quedan pendientes explícitas.

## Bloque 2 — Sacrificios y Banco

2.1 Altar disponible en acto II, selección una vez por UUID e irreversible aun al morir/revivir/reiniciar. Opciones:

- Abrigo: inmune tormenta/frío; mobs te detectan a 32 bloques.
- Cama: dormir funciona; −2 corazones máximos.
- Ojo: ves mobs tras paredes 16 bloques; mobs te apuntan primero.
- Pulmón: respiras bajo agua; velocidad en tierra −15%.
- Estómago: comida sana y no se agota; sin pociones.
- Corazón: +4 corazones; sin armadura superior a hierro.
- Ancla: reapareces en el punto de muerte una vez; el grupo paga 1 Núcleo. NO debe conceder otra vuelta ni saltarse La Fosa obligatoria.

Menú muestra elecciones de todos antes de confirmar. Estado UUID en archivo temporada + PDC; elección pública `/pd sacrificios`.

2.2 Banco Eclipse: altar compartido, fondo colectivo, no inventarios individuales.

**Versión vigente (aprobada por el usuario) — tres compras, sin más:**
- **12 Núcleos** → extensión única de 3 días, tope 45 días totales, fecha real siempre visible.
- **8 Núcleos** → abre el ritual de resurrección (no paga Chispas del muerto).
- **30 Núcleos** → enciende el sol el último día (victoria).

Sin sacrificio extra y sin Chispas globales en el banco. Chispas son exclusivamente personales. No crear conversión Esquirlas→Chispas ni coste nuevo sin aprobación.

~~Tabla del borrador (sustituida): 12 Núcleos retrasa Eclipse 3 d; 8 Núcleos + 4 Chispas abre ritual; 20 Esquirlas sacrificio extra; 30 Núcleos enciende sol.~~ Queda como registro histórico; no implementarla. La entrega de Banco requiere gate de aprobación antes de B2.

2.3 `/pd nucleo`, `esquirla`, `sello`, `mascara`, `cartucho` requieren `permadeath.debug` default false + `debug-commands: false`; cerrar también aliases como `filtro` para que no haya bypass. Ítems entran por cofres, Cacería, Fortaleza, Caída del Sol (evento programado con fragmentos anunciados), Fosa, Banco.

DoD: altar 7 opciones, irreversible persistente, banco con compras aprobadas, debug off, tests de irreversibilidad.

## Bloque 3 — La Fosa y Chispas

3.1 Run de 10–15 min en recinto Reserva. Ganancia **1 Chispa por run**, tope **1 por día real**, **tope 6 Chispas ganadas por UUID por temporada**, ledger persistente por UUID. Jugable solo, sin anuncios globales.

3.2 Caminos por vivos del roster:
- ≥3 vivos: ritual completo (2 vivos + Sello), coste = *N* Chispas según la vuelta.
- 2 vivos: ritual mínimo (1 vivo + Sello), coste = *N* Chispas según la vuelta.
- ≤1 vivo: altar Fosa sin vivos, coste = *N* Chispas + 1 Núcleo del banco.

Reutilizar `ResurrectionRitual`, no un sistema paralelo que omita `revived`/`revivals`.

3.3 Casos: muerto solo conectado con 8 vivos no vuelve, juega y espera; todos offline igual, sin spam; **válvula 5 días reales muerto con Chispas suficientes para el siguiente coste** permite vuelta solitaria aunque haya vivos; último vivo muere → Fosa → puede volver solo; 0 vivos y nadie conectado → fin de temporada con estadísticas; muerto abandona → su sitio sigue. La referencia del borrador a Chispas globales del banco está en contradicción con las reglas cerradas: manda la decisión del gate; no inventar un segundo ledger con monedas duplicadas.

3.4 `/pd revivirforzado` no consume Chispas ni vuelta, no altera el contador; queda como acción administrativa registrada en log.

DoD: Chispas solo, 3 caminos por roster, válvula 5 d, último vivo puede levantarse, vueltas escaladas 1/2/3 hasta 3 por temporada.

## Bloque 4 — Último Día

Fase 100 existente apaga el sol. Con 30 Núcleos del banco el grupo enciende el sol: victoria y pantalla final de estadísticas. Sin ellos, derrota y la misma pantalla. Días sobrevividos, muertes, quién llegó vivo, núcleos, eventos, sacrificios. No reliquias ni ciclos múltiples, no borrar/regenerar mundos. Resolver el plazo conforme al gate de extensión, no por reset implícito.

## Guardarraíles

Añadir y conectar sin reescribir sistemas. Un dueño por archivo; puntos calientes `Plugin`/`PhaseTasks` siempre seriales. Tests antes de cambiar lógica numérica; no quitar tests ni convertir `skipped` en PASS. Compilación no equivale a reproducir en cliente. No declarar verificado servidor sin pruebas. Commits solo si el usuario pide. Estado transaccional de temporada en archivo propio (`season-state.yml`), no `config.yml` como base de datos.

FUERA DE ALCANCE: hotbar phase 100 (memoria/pérdida al reiniciar); `MAX_HEALTH` phase 70 no restaura; NightHunt tick 93% sin una tirada por noche; bedrock retrofit solo chunks nuevos. Mantener arreglos recientes: fuego vanilla, cofres loot una vez sin recovery, 1 creeper cada 5 Endermans, clear de invis al salir del End, bats en persecución.

ORDEN 1→2→3→4. Ningún bloque siguiente hasta build verde y revisión del anterior. Se puede paralelizar por archivos disjuntos dentro de un bloque.

## Enmiendas confirmadas (vigentes, mandan sobre el borrador anterior)

El usuario confirmó Banco provisional de tres compras: 12 Núcleos extensión única 3 d, tope 45 y fecha visible; 8 Núcleos abre ritual; 30 Núcleos enciende el sol. Sin sacrificio extra ni Chispas globales.

Confirmó literal «sisi, asi escalado»: vuelta *N* cuesta *N* Chispas (1, 2, 3), tope 6 Chispas ganadas por UUID por temporada, máximo 3 vueltas. Esto **SUSTITUYE** la regla antigua de una sola vuelta y los precios 1/2/4.

El coste depende del número de vuelta; los requisitos de ritual sí dependen del roster: ≥3 vivos 2 participantes + Sello; 2 vivos 1 participante + Sello; ≤1 altar Fosa + 1 Núcleo del banco.

Válvula de 5 días muerto con Chispas suficientes para el siguiente coste. Ledger `revivals.<uuid>` por temporada; `revived.<uuid>` historial, no bloqueo. `/pd revivirforzado` no consume Chispas ni vuelta ni altera el contador; log administrativo. Ganancia 1/run, 1/día real, tope 6.

El objetivo I (portal) original sigue persistente: alternativa refugio **no aprobada**, no sustituir sin instrucción.

## Supuestos y pendientes

- `season-state.yml` (UUID + `seasonId`) es obligatorio; no usar `config.yml` como BD. Sin defaults que destruyan datos o bloqueen al administrador por falta de configuración.
- Prueba en servidor Paper y en cliente: **PENDIENTE**. No ejecutada en esta tarea documental; no se declara verificada.
- Línea base de tests: obtenerla del build real, no del borrador.
