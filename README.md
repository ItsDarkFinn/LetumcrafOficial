# Permadeath Escalating

Plugin para Paper **26.3** (Java **25**) con un calendario de días reales persistente. El día 1 se inicia al arrancar el plugin por primera vez.

## Incluido en 0.2.32

- Baneo permanente al morir (`permadeath.bypassdeath` permite exceptuar administradores).
- Anuncio de muerte en pantalla para todos: título “Letumcraft” y debajo el nombre del jugador que murió.
- `/pd estado`, `/pd dia <número>` y `/pd auto` para consultar, forzar un día de prueba y volver al calendario real.
- Las fases de los días 10, 20, 25, 35, 40, 50, 60, 70, 80, 90 y 100 de la propuesta: efectos, spawns, alteraciones de bloques, eventos ambientales, dragón, portales, inventario y final de partida.
- Barra de fase y avisos cuando comienza un día o una fase.
- `/pd pausa`, `/pd reanudar`, `/pd fases`, `/pd fase <día> <on|off>`, `/pd muertos` y `/pd revivir <jugador>`.
- `/pd revivirforzado <jugador>` (permiso `permadeath.admin.forcerevive`, default op): restaura a un eliminado aunque ya consumiera su única resurrección normal; conserva el historial y no consume Ancla. La vía normal sigue limitada a una por UUID entre reinicios.
- `/pd mascara` entrega la Máscara Antigás (fábrica pública `ReservaPodrida.createMask`); `/pd cartucho [cantidad]` y `/pd filtro [cantidad]` entregan Cartuchos de Esporas con overflow seguro al suelo.
- Registro de eliminaciones por UUID en `dead-players.yml`, independiente del nombre actual del jugador.
- Los recorridos periódicos de entidades corren cada cinco segundos en vez de cada segundo.
- Reserva Podrida generada automáticamente desde el día 20, con camino, flora, ruinas antiguas que esconden cofres de loot y filtro de esporas; no requiere WorldEdit.
- Ancla del Último Aliento: receta desbloqueada en el día 35, textura/modelo personalizado y comando `/pd ancla` para que operadores prueben el ítem.
- Núcleo del Eclipse: objeto con nombre, textura/modelo personalizado y 5% de probabilidad de aparecer en cofres de botín de mazmorras y estructuras compatibles.
- Esquirla de Eclipse y Sello de Resurrección: objetos con nombre y textura/modelo personalizado. La Esquirla se consigue al completar la Cacería Nocturna o con `/pd esquirla` para pruebas; el Sello se fabrica con 4 Esquirlas y el Núcleo.
- Marcador de muerte: deja una valla de roble con la cabeza y skin del jugador en su ubicación de muerte, o en una superficie segura cercana sin reemplazar bloques existentes. Se puede desactivar y configurar con `death-marker.enabled` y `death-marker.search-radius`.
- Ritual del Sello: receta en cruz con 4 Esquirlas de Eclipse y el Núcleo al centro. Al usar el Sello sobre una cabeza-marcador, al menos dos participantes deben quedarse a 5 bloques durante 30 segundos mientras aguantan tres oleadas de 4, 5 y 6 zombis, esqueletos y arañas. Si quedan menos de dos cerca o alguien se desconecta, el Núcleo vuelve al activador (si está desconectado, queda como objeto protegido para él junto al marcador), las esquirlas y el Sello se pierden, y el que se alejó pierde 5 corazones máximos por una hora. Al completarlo, el eliminado puede volver una sola vez y reaparece al entrar sin inventario ni ender chest.
- Pale Warden y murciélagos del día 20: conserva el Creaking y su modelo/textura propios; queda ligado al Creaking Heart, se congela con la mecánica vanilla al ser observado y usa ataques telegrafiados de raíces y pulso. Los murciélagos atacan cada dos segundos con dos corazones de daño mágico (ignora armadura) y tienen 5% de soltar un libro con Protección V y Protección contra explosiones V.
- Amenazas del End desde el día 35: 25% de los shulkers de ciudad salen blindados; al hacer clic o golpear el marco de una élitra aparece junto al jugador el Custodio del Vacío (shulker élite); los Endermans generados naturalmente en el End invocan creepers eléctricos invisibles con mecha de medio segundo, radio 5 y explosión sin daño a bloques. La densidad se controla con `phases.35.end-threats.electric-creepers.endermans-per-creeper` (por defecto 5); si se omite, se acepta la clave clásica `per-enderman` (por ejemplo, `1` para el comportamiento histórico 1:1).
- Cacería Nocturna: entre los días 5 y 24 puede ocurrir una vez cada dos días; desde el día 25 hasta el 110, una vez cada tres días. Se elige al azar durante la noche y requiere 15 jugadores vivos conectados al comenzar. Cada participante debe matar 5 zombis y 5 esqueletos para recibir una Esquirla. Operadores pueden usar `/pd evento iniciar`, `/pd evento estado` y `/pd evento cancelar` para probarlo sin el requisito de 15 jugadores.
- Caravana: disponible desde el día 1 y con una tirada aleatoria por noche (10% por defecto), como máximo una aparición cada dos días. Se crea a 28–64 bloques de un jugador vivo en el Overworld: un cofre con comida, flechas e hierro, custodiado por 2 zombis full hierro con espada de diamante y 2 esqueletos full cota con arco. El equipo de los guardias no se puede obtener como botín. El cofre tiene 25% de probabilidad independiente de incluir un Núcleo del Eclipse y 1% total de contener un Tótem o una manzana de oro encantada, elegidos al azar. `/pd caravana iniciar` invoca una para probarla y `/pd caravana estado` muestra su estado.
- Tormenta de ceniza del Nether: disponible desde el día 20; aparece con 15% de probabilidad por día elegible, con un enfriamiento de 3 días entre eventos. Dura 5 minutos en un radio de 80 bloques alrededor del sitio: partículas de ceniza, Oscuridad durante 10 de cada 15 segundos y oleadas de 4 blazes más 2 ghasts cada 30 segundos (máximo 20 mobs del evento vivos a la vez). No modifica bloques y elimina los mobs de evento al terminar. Operadores pueden probarla con `/pd evento ceniza iniciar` estando en el Nether; `/pd evento ceniza estado` y `/pd evento ceniza cancelar` la administran.
- Cacería de Fortaleza del Nether: desde el día 10, una vez por día al haber jugadores vivos en el Nether y una fortaleza existente cercana. Si el jugador ya está dentro o junto a una fortaleza, detecta los ladrillos físicos cercanos en vez de depender solo del punto de búsqueda de Paper. Incluye tres oleadas de piglins zombificados y esqueletos Wither; la última tiene un capitán reforzado. El cofre protegido garantiza oro, cuarzo, comida y pociones de Resistencia al Fuego, con 10% independiente de Núcleo del Eclipse y 10% de lingote de netherite. `/pd evento fortaleza iniciar|estado|cancelar` permite probarla y administrarla.
- Striders Malditos del Nether: desde el día 10, 10% de los striders generados naturalmente se marcan con brillo y nombre rojo; tocar uno mata al jugador instantáneamente. Se puede ajustar la probabilidad bajo `random-events.nether.cursed-striders`.

El Ancla del Último Aliento usa el identificador `permadeath:anchor_last_breath`; el Núcleo del Eclipse usa `permadeath:eclipse_core`, la Esquirla usa `permadeath:eclipse_shard` y el Sello usa `permadeath:resurrection_seal`. Para ver estos objetos y la textura pálida del Creaking/Pale Warden, el cliente debe cargar `Letumcraft-ResourcePack-0.2.45.zip`. La textura cambia visualmente todos los Creakings para ese cliente.

El ritual se puede ajustar en `resurrection-ritual.enabled`, `duration-seconds`, `participant-radius` y `leaver-penalty-seconds`; las penalizaciones activas se guardan en `ritual-penalties.yml` y cuentan tiempo real, incluso si el jugador se desconecta.

La Cacería Nocturna se controla en `random-events`; `minimum-online-players`, `night-check-chance`, `test-duration-seconds` y `dungeon-core-chance` permiten ajustar sus requisitos y frecuencia. La caravana tiene su configuración propia bajo `random-events.caravan`; la Tormenta de ceniza usa `random-events.nether` y `random-events.nether.ashstorm`.

## Bloque 1 — Temporada: calendario, roster, actos y objetivos

- `season.id` (default `temporada-1`), `season.length-days` (default 40, rango 30–45; 100 solo compatibilidad y reproduce los umbrales legados), `season.roster: [UUID...]` (fuente de verdad cuando existe: M = tamaño real), `season.roster-size` (fallback 16 solo para cálculos), `season.gate-percent` (default 40) y `season.enforce-roster` (default true).
- Sin lista no se inventan UUID ni se enrola a nadie; el enforcement de login queda desactivado y se avisa en consola (configuración incompleta). Con lista válida, quien no está dentro recibe KICK_WHITELIST al entrar.
- N = roster menos eliminados de `DeathRegistry` (offline, nunca conectados). Con N<4 los efectos de fase se suspenden; bossbar, calendario y desbloqueos narrativos siguen. Al volver a 4 se reanudan.
- `isPhaseActive(fase)` = `phases.<fase>.enabled` && desbloqueo comprimido (`phaseDay = max(1, round(fase*len/100))`: Reserva 20→8, Ancla 35→14, Nether 10→4 con 40d) && N>=4. `/pd dia` y los umbrales de `config.yml` mantienen sus valores originales; con `season 100` y roster>=4 todo reproduce el comportamiento legado.
- Gate único automático para Cacería/Caravana/Ceniza/Fortaleza: `max(2, ceil(M*40/100), minimum-online-players)`. El `minimum-online-players` heredado (default 15) prevalece si es mayor (con M=16 el 40% da 7, pero 15 domina). Las pruebas manuales (`/pd evento|caravana iniciar`) omiten el gate.
- Bossbar de temporada: acto, día, vivos N/M, próxima fase y aviso visible de congelación con 3 vivos. Anuncios de acto, objetivos y recompensas persistidos 1 vez en `season-state.yml` (propio, con `seasonId`); nunca `config.yml` como BD. `/pd dia` hacia atrás no duplica.
- Objetivos alimentados por eventos reales: portal (teleport exitoso al mundo Reserva), primer Núcleo (recoger/click real), Fortaleza (abrir el cofre marcado tras 3 oleadas), Cacería (recompensa al participante), noche sin dormir (bed-enter + borde de día) + muestra de esporas (cartucho/filtro/máscara real), racha 3 días con N<5 (muertes/revives + borde de día). Sin escaneos periódicos. Sin proveedor de Banco/Chispas en B1: la recompensa queda pendiente con mensaje, sin excepción ni doble pago.
- Matriz manual mínima: `/pd temporada` (acto/día/vivos/objetivo), `/pd actos` (calendario), `/pd dia 8` desbloquea fase 20 con 40d, bossbar con acto y N/M, suspensión visible con 3 vivos y reactivación con 4.

## Bloque 2 — Sacrificios y Banco Eclipse

- Altar disponible desde el acto II (`/pd altar` o `/pd altar menu`; fijar con `/pd altar aqui`, permiso `permadeath.admin`, sin sobreescribir construcciones). 7 opciones, una elección irreversible por UUID (durable en `season-state.yml`, sobrevive muerte/revive/reinicio): Abrigo (inmune tormenta/frío, detectado a 32), Cama (dormir funciona, −2 corazones), Ojo (mobs visibles tras paredes a 16 por brillo vanilla + prioridad hostil), Pulmón (respira bajo agua, −15% velocidad en tierra), Estómago (comida sana/sin agotamiento, sin pociones), Corazón (+4 corazones, sin armadura superior a hierro en todas las rutas), Ancla (reaparece en el punto de muerte una vez; el grupo paga 1 Núcleo; no concede vuelta gratis ni salta La Fosa). El menú muestra las elecciones públicas de todos antes de confirmar (doble clic, anti-robo, cerrar no cobra). `/pd sacrificios` lista lo público.
- Ventajas/costes reales en eventos (sin flags sin consumidor): Cama evita la explosión UHC solo al elegido; Abrigo excluye clima/frío pero ningún otro daño; Estómago bloquea beber/salpicadura/nube de poción sin bloquear otros ataques mágicos; Corazón bloquea equipar diamante/netherite (clic/shift/drag/interactuar/dispensador/hotbar/re-log, con evento de armadura de Paper y tarea de seguridad) con modificadores estables (−4 HP Cama, +8 HP Corazón, base 14 de fase 70 intacta, limpieza de duplicados al reentrar); Ojo por GLOWING vanilla acotado (tope 20, radio 16) con limitación informada (depende del render del cliente); re-objetivos acotados con tope, sin escaneos globales.
- Banco colectivo junto al altar: depósitos solo de Núcleos PDC auténticos desde el inventario (`/pd banco depositar`, exige cercanía al altar si está fijado; sin argumentos negativos ni duplicación: quita los ítems y luego acredita). Tres compras aprobadas y nada más: 12 Núcleos extensión única +3d (tope 45, rechaza 44+3>45 sin cobrar ni clampar), 8 Núcleos abre el ritual global persistente, 30 Núcleos reserva/pago del Sol del último día (escrow + confirmación/cancelación idempotentes). Saldo nunca negativo, sin overdraft, tx idempotentes (replay no cobra dos veces). Sin Chispas en el banco (personales) y sin conversión de Esquirlas.
- Política de gasto: solo `permadeath.bank` (default op) consume el fondo, siempre con doble confirmación (`/pd banco comprar <extension|ritual|sol>` dos veces + `/pd banco sol <confirmar|cancelar> <id>`); ningún jugador aleatorio destruye fondos sin confirmar. API para B3 en `SeasonEconomyService` (gastos, estado del ritual, escrow/tx).
- Debug cerrado: `/pd nucleo|esquirla|sello|mascara|cartucho|filtro` exigen AMBOS permiso `permadeath.debug` (default false) Y `debug-commands:true` en config (default false); sin bypass por alias. Los ítems entran por cofres, Cacería, Fortaleza, Caída del Sol (evento programado día 30, 3 Núcleos auténticos, un solo intento persistido, sin fuego/bloques/spam), Fosa y Banco; nada gratis al conectarse.
- Estado en `season-state.yml` propio (sacrificios, banco, escrow/tx, Caída del Sol, Ancla); `config.yml` nunca es BD. No se toca Fosa/`DeathRegistry.revive`/`revived` en este bloque; no se tocan hotbar100/health70/NightHunt93%/bedrock legacy, fuego vanilla, cofres una vez, creepers 1/5, bats/Invis/StrengthI.

## Bloque 4 — Último Día (final de temporada)

- Fecha real del fin = base (`season.length-days` 30–45, default 40) + única extensión de 3 días si se compró (12 Núcleos, tope 45); compat 100 conserva 100. La extensión solo retrasa el fin, no recomprime actos/fases. `/pd temporada` y `/pd actos` muestran siempre el fin real y el actor final en todos los actos; con base 40 + extensión el final es el día 43 (no hay anuncio de final el día 40).
- Al día final, con 30 Núcleos en el Banco el grupo enciende el Sol (victoria); sin ellos, derrota. El pago usa tx determinista `finale-sun:<seasonId>:<día>` sobre el escrow idempotente del Banco: repetirlo tras un reinicio no cobra dos veces. Estado único RUNNING→VICTORY/DEFEAT en `season-state.yml` (con `seasonId` + UUID); `/pd dia` hacia atrás o reinicios no reabren ni duplican premios.
- Disparo en el borde de día (`DayManager`→`SeasonService.checkDayRollover/handleDayChanged`) y al arrancar tras offline largo; la señal B3 (0 vivos del roster) produce fin con estadísticas sin mantener muerte activa por error. Tras el final: se congela nuevo run/compra/revive/ranking y `/pd fosa volver`/`/pd revivir` se deniegan; el roster (incluidos muertos) puede entrar a ver el final sin patadas.
- Pantalla final: broadcast + title a conectados y `/pd final` (chat, accesible a todo el roster incluidos muertos) con días sobrevividos (primer visto por join), muertes de por vida por UUID (por hook, sobrevive al revive), quién llegó vivo, núcleos reunidos/gastados, eventos completados y sacrificios. Sin reliquias ni multiciclos; al terminar no se borran mundos, no se reinicia el mapa ni hay kicks.
- Comandos: `/pd final` (ver final y estadísticas), `/pd temporada` y `/pd actos` (incluyen fin real y actor).
- Tests: `SeasonFinaleTest` (12 tests puros, sin Bukkit): fecha base/extensión/tope 45/compat 100, no disparo el día 40 si el fin es 43, victoria/derrota por saldo, extinción temprana por 0 vivos, pago idempotente sin doble cobro, fallo sin fondos sin mutar, estados terminados rechazan transiciones, persistencia única en `SeasonState`, stats que sobreviven al revive, round-trip `SeasonStore`.
- DoD implementado: fecha real, extensión única, tope, idempotencia, persistencia, estados rechazados, freeze, stats por hooks, `/pd final`. Pendiente manual (Paper/cliente PENDIENTE, no ejecutado aquí): día final real con 30 Núcleos (victoria) y sin ellos (derrota), extensión 40→43 sin anuncio el 40, extinción 0 vivos con fin visible, `/pd dia` adelante/atrás sin duplicar ni esconder el fin, reinicio/kill -9 en el borde del final sin doble cobro, `/pd final` visible para muertos, sin regresiones B1–B3.
- Limitaciones: el auto-encendido consume del Banco al día final si hay fondos (una vez, tx idempotente); si el disco falla entre el pago y el sellado, el siguiente arranque lo reconcilia por el mismo tx. Fuera de alcance intacto: hotbar100/health70/NightHunt93%/bedrock legacy, fuego vanilla, cofres una vez, creepers 1/5, Invis/Bats/StrengthI.

## Paso 7 — Integración (contratos, lifecycle y pack)

- `PermadeathPlugin` conserva la referencia de `PhaseTasks` (`phaseTasks()`) y la detiene en `onDisable` (`stop()`: cancela el tick y restaura velocidades/navegación de huida). Sin otros registros nuevos: los componentes de los pasos 2–6 ya se auto-registran.
- Contratos entre paquetes: `ReservaPortalController.isCustomPortal(Location)` público y puro (lo resuelve `DifficultyListener` por reflexión para no transformar ni dañar portales custom); `ReservaPodrida.createMask()` público para `/pd mascara`; `DeathRegistry` muta por UUID (`revive` normal una sola vez, `reviveForced` administrativa con historial).
- Clima y bedrock: fases 40/100 sin tormenta periódica (`/weather clear` persiste); lluvia ácida solo bajo lluvia real, al aire libre y en biomas con precipitación. Sello de bedrock solo en chunks nuevos de `Letumcraft_Reserva_v7`; sin regeneración ni migración automática (ver notas en `config.yml`).
- Pruebas: `gradlew build` con JDK 25 compila y corre la suite de regresión (187 tests; 4 omitidos porque requieren servidor Paper real), incluidos `DifficultyListenerTest`, `FortressHuntEventTest`, `DeathRegistryTest`, `PhaseTasksTest`, `ReservaRegressionTest`, `SacrificeArmorTest` y `EndThreatControllerTest`; además empaqueta `build/dist/Letumcraft-ResourcePack-<versión>.zip` con `pack.mcmeta` en raíz. Sin JDK 25 ni servidor Paper no hay build válido (ver paso 8).
- Backlog (sin implementar): cuatro mobs distintos darían piezas de armadura de netherite encantada; selección, probabilidades y encantamientos pendientes de diseño.

## Aviso importante

Desde el día 100 este plugin puede eliminar bedrock y modificar bloques del mundo. Haz un respaldo del mundo y prueba primero con `/pd dia 100` en una copia de tu servidor.

## Compilar e instalar

1. Copia el JAR de la versión actual a la carpeta `plugins` de tu servidor Paper 26.3 y reinícialo.
2. Para modificar el código, abre esta carpeta como proyecto Gradle en IntelliJ IDEA y ejecuta `gradle build`.

El archivo `plugins/LetumcrafOficial/config.yml` conserva la hora de inicio y permite editar los mensajes y el estado de la muerte permanente.

# Reserva Podrida (primera versión jugable)

Desde el día 20, cualquier jugador puede entrar por una ruina antigua que el plugin coloca en un claro natural cerca del spawn; no modifica construcciones existentes y, si no encuentra un sitio seguro, no construye nada. Al cruzar la entrada se guarda el punto de retorno; otra ruina junto al spawn de la Reserva permite volver. `/pd reserva` y `/pd reserva salir` siguen disponibles para pruebas y administración. El mundo se crea automáticamente la primera vez: manglar/jungla subterránea infinita, techo cerrado, camino estrecho de ladrillos de lodo con cruces y ramales diagonales, árboles de jungla/manglar de varios tamaños y siluetas, vegetación y cofres con suministros y loot de aventura.

El aire causa daño, Náusea y Oscuridad cada 5 segundos si no llevas la Máscara Antigás y un Cartucho de Esporas. El cartucho se consume automáticamente y protege 10 minutos. Para pruebas, un administrador puede obtener cartuchos con `/pd filtro [cantidad]`. La receta del cartucho usa panal, carbón vegetal y papel en una fila y entrega dos; los Portadores de Esporas también pueden soltar uno.

La configuración `reserve` aparece en `plugins/PermadeathEscalating/config.yml`: permite activar/desactivar el lugar, cambiar día de acceso, duración del cartucho y probabilidad de ruinas por chunk. Las modificaciones del mundo se guardan normalmente y no requieren WorldEdit. La generación actual añade árboles viejos con troncos torcidos, ramas laterales y raíces anchas, hojas persistentes y un suelo de colinas y crestas. Cada punto de loot aparece dentro de una casa antigua parcialmente derruida; el cofre se llena con suministros y botín de aventura al abrirse por primera vez, incluidos cofres vacíos de ruinas generadas en versiones anteriores. El botín de la Reserva, la Caravana y la Cacería se reparte en ranuras aleatorias. Se crea como `Letumcraft_Reserva_v7` para no alterar reservas anteriores. Las esporas se muestran continuamente con partículas verdes y esporas flotantes. Un 12% de los zombis naturales puede aparecer como Portador de Esporas, con armadura teñida, Fuerza II y probabilidad de soltar un cartucho; un 10% de las arañas puede aparecer como Acechador de Raíces invisible, con Fuerza II, veneno al atacar y drops de raíces de mangle. La receta de la Máscara Antigás usa un casco de diamante al centro, pepitas de hierro en las cuatro esquinas, paneles de cristal arriba y a los lados, y panal o bloque de panal abajo al centro. La máscara añade +1 de armadura, +2 de resistencia de armadura, Protección II y durabilidad de casco de diamante; con un Cartucho de Esporas bloquea el daño de las esporas durante 10 minutos. El paquete de recursos incluye los iconos personalizados de la máscara y del cartucho; usa `Letumcraft-ResourcePack-0.2.45.zip`.

## Paso 1 — Preparación, contratos y harness (solo este paso está autorizado)

Tarea activa: preparar ejecución y reproducciones. No empezar pasos 2–8. Sin commits, sin instalaciones, sin regeneración de mundos ni shell directo.

### Toolchain y bloqueo de validación

- `build.gradle.kts` mantiene `toolchain.languageVersion = 25` (Paper 26.3 / Java 25). No cambiar el objetivo a Java 8.
- Java activo informado es 8. Inspección por lectura (sin shell): no hay evidencia en el repo de un JDK 25 instalado; `gradlew`/`gradlew.bat` solo resuelven `JAVA_HOME`/PATH. No se instala nada en este paso.
- Bloqueo: `gradlew test/build` no es válido hasta resolver JDK 25 vía `JAVA_HOME`. Se solicita autorización para instalarlo o ruta a un JDK 25 existente.

### Contratos compartidos (los editores los implementan después, no en este paso)

1. `ReservaPodrida.createMask()` debe ser `public` (hoy `private` en `src/main/java/mx/vicko/permadeath/ReservaPodrida.java:180`). Factory para `/pd mascara`.
2. `ReservaPortalController.isCustomPortal(Location)` debe ser `public`, sin efectos secundarios, para uso de `DifficultyListener` (hoy solo existen `isCustomDoor`/`isReserveExit` privados en `src/main/java/mx/vicko/permadeath/ReservaPortalController.java:337,347`). No transformar ni dañar antes de clasificar.
3. `DeathRegistry` API por UUID: vía normal (una sola resurrección, conserva historial) + vía forzada explícita por UUID (permiso administrativo, nunca borra `revived` ni reinicia contador). Hoy solo existe `revive(String)` por nombre en `src/main/java/mx/vicko/permadeath/DeathRegistry.java:45`.

### Harness mínimo de tests

- Solo JUnit 5 (`junit-jupiter:5.11.4` + `junit-platform-launcher`, `useJUnitPlatform()`). No se asume MockBukkit/Paper test harness disponible.
- Los tests de los pasos 2–6 deben ser unitarios puros o con stubs manuales mínimos; lo que requiera servidor (navegación, vuelo Bat, portales vanilla/custom, snapshots Chest, pack/recetario, flick de cámara) queda como verificación manual en servidor Paper con copia y backup, no como test verde.
- Rutas previstas: `src/test/java/mx/vicko/permadeath/DifficultyListenerTest.java`, `FortressHuntEventTest.java`, `DeathRegistryTest.java`, `PhaseTasksTest.java`, `ReservaRegressionTest.java`.

### Precisiones verificadas contra código (no hipótesis)

- Lluvia ácida actual: corre en `PhaseTasks.tick()` cada 1 s (`runTaskTimer(plugin, this::tick, 20L, 20L)` en `PhaseTasks.java:56-57`, `applyClimatePhase` vía `applyPlayerPhases` en `PhaseTasks.java:140,214-227`). NO es un tick de servidor global como afirma un diagnóstico.
- Informes en `.empryo/background/bg-1/bg-2` son hipótesis: verificar siempre contra fuentes antes de implementar.
- Bugs visuales (texturas, cofres vacíos, fireball, cámara): no declararlos corregidos sin reproducción.

### Reparto vigente

Cambio autorizado por el usuario: todos los agentes de esta ejecución usan Muse Spark 1.3 Contributor en OpenCode Go, esfuerzo xhigh; reemplaza el reparto anterior MiniMax/DeepSeek. Este paso no asigna ni inicia otros paquetes.

