# Permadeath Escalating

Plugin para Paper **26.3** (Java **25**) con un calendario de días reales persistente. El día 1 se inicia al arrancar el plugin por primera vez.

## Incluido en 0.2.32

- Baneo permanente al morir (`permadeath.bypassdeath` permite exceptuar administradores).
- Anuncio de muerte en pantalla para todos: título “Letumcraft” y debajo el nombre del jugador que murió.
- `/pd estado`, `/pd dia <número>` y `/pd auto` para consultar, forzar un día de prueba y volver al calendario real.
- Las fases de los días 10, 20, 25, 35, 40, 50, 60, 70, 80, 90 y 100 de la propuesta: efectos, spawns, alteraciones de bloques, eventos ambientales, dragón, portales, inventario y final de partida.
- Barra de fase y avisos cuando comienza un día o una fase.
- `/pd pausa`, `/pd reanudar`, `/pd fases`, `/pd fase <día> <on|off>`, `/pd muertos` y `/pd revivir <jugador>`.
- Registro de eliminaciones por UUID en `dead-players.yml`, independiente del nombre actual del jugador.
- Los recorridos periódicos de entidades corren cada cinco segundos en vez de cada segundo.
- Reserva Podrida generada automáticamente desde el día 20, con camino, flora, ruinas antiguas que esconden cofres de loot y filtro de esporas; no requiere WorldEdit.
- Ancla del Último Aliento: receta desbloqueada en el día 35, textura/modelo personalizado y comando `/pd ancla` para que operadores prueben el ítem.
- Núcleo del Eclipse: objeto con nombre, textura/modelo personalizado y 5% de probabilidad de aparecer en cofres de botín de mazmorras y estructuras compatibles.
- Esquirla de Eclipse y Sello de Resurrección: objetos con nombre y textura/modelo personalizado. La Esquirla se consigue al completar la Cacería Nocturna o con `/pd esquirla` para pruebas; el Sello se fabrica con 4 Esquirlas y el Núcleo.
- Marcador de muerte: deja una valla de roble con la cabeza y skin del jugador en su ubicación de muerte, o en una superficie segura cercana sin reemplazar bloques existentes. Se puede desactivar y configurar con `death-marker.enabled` y `death-marker.search-radius`.
- Ritual del Sello: receta en cruz con 4 Esquirlas de Eclipse y el Núcleo al centro. Al usar el Sello sobre una cabeza-marcador, al menos dos participantes deben quedarse a 5 bloques durante 30 segundos mientras aguantan tres oleadas de 4, 5 y 6 zombis, esqueletos y arañas. Si quedan menos de dos cerca o alguien se desconecta, el Núcleo vuelve al activador (si está desconectado, queda como objeto protegido para él junto al marcador), las esquirlas y el Sello se pierden, y el que se alejó pierde 5 corazones máximos por una hora. Al completarlo, el eliminado puede volver una sola vez y reaparece al entrar sin inventario ni ender chest.
- Pale Warden y murciélagos del día 20: conserva el Creaking y su modelo/textura propios; queda ligado al Creaking Heart, se congela con la mecánica vanilla al ser observado y usa ataques telegrafiados de raíces y pulso. Los murciélagos atacan cada dos segundos con dos corazones de daño mágico (ignora armadura) y tienen 5% de soltar un libro con Protección V y Protección contra explosiones V.
- Amenazas del End desde el día 35: 25% de los shulkers de ciudad salen blindados; al hacer clic o golpear el marco de una élitra aparece junto al jugador el Custodio del Vacío (shulker élite); cada Enderman generado naturalmente en el End genera un creeper eléctrico invisible con mecha de medio segundo, radio 5 y explosión sin daño a bloques.
- Cacería Nocturna: entre los días 5 y 24 puede ocurrir una vez cada dos días; desde el día 25 hasta el 110, una vez cada tres días. Se elige al azar durante la noche y requiere 15 jugadores vivos conectados al comenzar. Cada participante debe matar 5 zombis y 5 esqueletos para recibir una Esquirla. Operadores pueden usar `/pd evento iniciar`, `/pd evento estado` y `/pd evento cancelar` para probarlo sin el requisito de 15 jugadores.
- Caravana: disponible desde el día 1 y con una tirada aleatoria por noche (10% por defecto), como máximo una aparición cada dos días. Se crea a 28–64 bloques de un jugador vivo en el Overworld: un cofre con comida, flechas e hierro, custodiado por 2 zombis full hierro con espada de diamante y 2 esqueletos full cota con arco. El equipo de los guardias no se puede obtener como botín. El cofre tiene 25% de probabilidad independiente de incluir un Núcleo del Eclipse y 1% total de contener un Tótem o una manzana de oro encantada, elegidos al azar. `/pd caravana iniciar` invoca una para probarla y `/pd caravana estado` muestra su estado.
- Tormenta de ceniza del Nether: disponible desde el día 20; aparece con 15% de probabilidad por día elegible, con un enfriamiento de 3 días entre eventos. Dura 5 minutos en un radio de 80 bloques alrededor del sitio: partículas de ceniza, Oscuridad durante 10 de cada 15 segundos y oleadas de 4 blazes más 2 ghasts cada 30 segundos (máximo 20 mobs del evento vivos a la vez). No modifica bloques y elimina los mobs de evento al terminar. Operadores pueden probarla con `/pd evento ceniza iniciar` estando en el Nether; `/pd evento ceniza estado` y `/pd evento ceniza cancelar` la administran.
- Cacería de Fortaleza del Nether: desde el día 10, una vez por día al haber jugadores vivos en el Nether y una fortaleza existente cercana. Si el jugador ya está dentro o junto a una fortaleza, detecta los ladrillos físicos cercanos en vez de depender solo del punto de búsqueda de Paper. Incluye tres oleadas de piglins zombificados y esqueletos Wither; la última tiene un capitán reforzado. El cofre protegido garantiza oro, cuarzo, comida y pociones de Resistencia al Fuego, con 10% independiente de Núcleo del Eclipse y 10% de lingote de netherite. `/pd evento fortaleza iniciar|estado|cancelar` permite probarla y administrarla.
- Striders Malditos del Nether: desde el día 10, 10% de los striders generados naturalmente se marcan con brillo y nombre rojo; tocar uno mata al jugador instantáneamente. Se puede ajustar la probabilidad bajo `random-events.nether.cursed-striders`.

El Ancla del Último Aliento usa el identificador `permadeath:anchor_last_breath`; el Núcleo del Eclipse usa `permadeath:eclipse_core`, la Esquirla usa `permadeath:eclipse_shard` y el Sello usa `permadeath:resurrection_seal`. Para ver estos objetos y la textura pálida del Creaking/Pale Warden, el cliente debe cargar `../Letumcraft-ResourcePack-0.2.10.zip`. La textura cambia visualmente todos los Creakings para ese cliente.

El ritual se puede ajustar en `resurrection-ritual.enabled`, `duration-seconds`, `participant-radius` y `leaver-penalty-seconds`; las penalizaciones activas se guardan en `ritual-penalties.yml` y cuentan tiempo real, incluso si el jugador se desconecta.

La Cacería Nocturna se controla en `random-events`; `minimum-online-players`, `night-check-chance`, `test-duration-seconds` y `dungeon-core-chance` permiten ajustar sus requisitos y frecuencia. La caravana tiene su configuración propia bajo `random-events.caravan`; la Tormenta de ceniza usa `random-events.nether` y `random-events.nether.ashstorm`.

## Aviso importante

Desde el día 100 este plugin puede eliminar bedrock y modificar bloques del mundo. Haz un respaldo del mundo y prueba primero con `/pd dia 100` en una copia de tu servidor.

## Compilar e instalar

1. Copia el JAR de la versión actual a la carpeta `plugins` de tu servidor Paper 26.3 y reinícialo.
2. Para modificar el código, abre esta carpeta como proyecto Gradle en IntelliJ IDEA y ejecuta `gradle build`.

El archivo `plugins/LetumcrafOficial/config.yml` conserva la hora de inicio y permite editar los mensajes y el estado de la muerte permanente.

# Reserva Podrida (primera versión jugable)

Desde el día 20, cualquier jugador puede entrar por una ruina antigua que el plugin coloca en un claro natural cerca del spawn; no modifica construcciones existentes y, si no encuentra un sitio seguro, no construye nada. Al cruzar la entrada se guarda el punto de retorno; otra ruina junto al spawn de la Reserva permite volver. `/pd reserva` y `/pd reserva salir` siguen disponibles para pruebas y administración. El mundo se crea automáticamente la primera vez: manglar/jungla subterránea infinita, techo cerrado, camino estrecho de ladrillos de lodo con cruces y ramales diagonales, árboles de jungla/manglar de varios tamaños y siluetas, vegetación y cofres con suministros y loot de aventura.

El aire causa daño, Náusea y Oscuridad cada 5 segundos si no llevas la Máscara Antigás y un Cartucho de Esporas. El cartucho se consume automáticamente y protege 10 minutos. Para pruebas, un administrador puede obtener cartuchos con `/pd filtro [cantidad]`. La receta del cartucho usa panal, carbón vegetal y papel en una fila y entrega dos; los Portadores de Esporas también pueden soltar uno.

La configuración `reserve` aparece en `plugins/PermadeathEscalating/config.yml`: permite activar/desactivar el lugar, cambiar día de acceso, duración del cartucho y probabilidad de ruinas por chunk. Las modificaciones del mundo se guardan normalmente y no requieren WorldEdit. La generación actual añade árboles viejos con troncos torcidos, ramas laterales y raíces anchas, hojas persistentes y un suelo de colinas y crestas. Cada punto de loot aparece dentro de una casa antigua parcialmente derruida; el cofre se llena con suministros y botín de aventura al abrirse por primera vez, incluidos cofres vacíos de ruinas generadas en versiones anteriores. El botín de la Reserva, la Caravana y la Cacería se reparte en ranuras aleatorias. Se crea como `Letumcraft_Reserva_v7` para no alterar reservas anteriores. Las esporas se muestran continuamente con partículas verdes y esporas flotantes. Un 12% de los zombis naturales puede aparecer como Portador de Esporas, con armadura teñida, Fuerza II y probabilidad de soltar un cartucho; un 10% de las arañas puede aparecer como Acechador de Raíces invisible, con Fuerza II, veneno al atacar y drops de raíces de mangle. La receta de la Máscara Antigás usa un casco de diamante al centro, pepitas de hierro en las cuatro esquinas, paneles de cristal arriba y a los lados, y panal o bloque de panal abajo al centro. La máscara añade +1 de armadura, +2 de resistencia de armadura, Protección II y durabilidad de casco de diamante; con un Cartucho de Esporas bloquea el daño de las esporas durante 10 minutos. El paquete de recursos incluye los iconos personalizados de la máscara y del cartucho; usa `Letumcraft-ResourcePack-0.2.18.zip`.

