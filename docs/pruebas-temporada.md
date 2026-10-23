# Guía de pruebas de temporada — LetumcrafOficial

Documento de pruebas manuales en copia de servidor con backup. No es código ni promesa de producción.
B4 (Bloque 4 / final de temporada) está en revisión: los casos de final se PRUEBAN, no se presupone éxito. Compilar no equivale a reproducir en cliente/servidor.

## 0. Preparación (obligatoria)

- Backup completo del mundo y de plugins/ antes de tocar nada. Trabajar solo en copia del servidor; nunca en mundo activo.
- No resetear/regenerar mundos activos ni aceptar EULA automáticamente. Si el servidor pide EULA, aceptarla manualmente en la copia de prueba.
- Java 25 (JDK instalado en C:\Users\cerez\AppData\Local\LetumcraftTools\jdk-25.0.4.1+1 o equivalente). JAVA_HOME apuntando a ese JDK. No cambiar toolchain.
- Instalar JAR build/libs/PermadeathEscalating-0.2.45.jar en plugins/ de la copia y el pack build/dist/Letumcraft-ResourcePack-0.2.45.zip en el cliente.
- Roster UUID: editar plugins/.../config.yml -> season.roster: [UUID,...] (fuente de verdad). M = tamaño real. Sin lista no se inventan UUID; enforcement desactivado con aviso. No autorrellenar.
- debug-commands: false por defecto. Solo activar en copia de prueba y regresar a false al acabar. Los comandos debug (/pd nucleo, esquirla, sello, mascara, cartucho, filtro) exigen AMBOS permiso permadeath.debug (default false) y debug-commands: true. Cerrar también alias filtro.
- Tiempo real: algunos casos exigen esperar días reales. /pd dia <n> fuerza día para pruebas de desbloqueo, pero no promete acelerar la época ni el ledger diario de La Fosa. Para casos de "1 por día real" esperar el tiempo indicado.

### Formato de registro

Tabla por caso:

| Paso | Acción | Esperado | Resultado [ ] |
|---|---|---|---|

Datos a reportar si falla: versión JAR/pack, season.id, season.length-days, roster (UUIDs), día actual (/pd temporada), salida de /pd actos, /pd final, logs del servidor (consola), season-state.yml, dead-players.yml, pasos reproducibles, capturas.

## 1. Bloque 1 — Temporada, calendario, roster, actos, gates

### 1.1 Calendario 40 días y tablas

> ADVERTENCIA: no ejecutar /pd dia 40 ni /pd dia 43 hasta B4. Retroceder con /pd dia NO reabre la temporada. La fila 13 es solo referencia de prueba FINAL; no ejecutar hasta acabar B1–B3.

| Paso | Acción | Esperado | Resultado [ ] |
|---|---|---|---|
| 1 | /pd temporada con length-days: 40 | Acto, día, próximo acto, vivos N/M, objetivo |  |
| 2 | /pd actos | Calendario completo con días de fase |  |
| 3 | /pd dia 8 | Desbloquea fase 20 (con 40d) |  |
| 4 | /pd dia 4 | Desbloquea fase 10 |  |
| 5 | /pd dia 10 | Desbloquea fase 25 |  |
| 6 | /pd dia 14 | Desbloquea fase 35 |  |
| 7 | /pd dia 16 | Desbloquea fase 40 |  |
| 8 | /pd dia 20 | Desbloquea fase 50 |  |
| 9 | /pd dia 24 | Desbloquea fase 60 |  |
| 10 | /pd dia 28 | Desbloquea fase 70 |  |
| 11 | /pd dia 32 | Desbloquea fase 80 |  |
| 12 | /pd dia 36 | Desbloquea fase 90 |  |
| 13 | (Referencia FINAL, no ejecutar hasta acabar B1–B3) /pd dia 40 | Prueba de final día 40/43; usar copia independiente desde backup PRE-final con servidor apagado |  |

Verificar tabla phaseDay con 40: 10->4, 20->8, 25->10, 35->14, 40->16, 50->20, 60->24, 70->28, 80->32, 90->36, 100->40. Con 30 días: 3,6,8,11,12,15,18,21,24,27,30. Con 100 reproducir umbrales legados.

### 1.2 Roster cerrado y freeze 3->4

| Paso | Acción | Esperado | Resultado [ ] |
|---|---|---|---|
| 1 | Configurar season.roster con M UUIDs | M = tamaño real |  |
| 2 | Jugador no roster intenta entrar | KICK_WHITELIST |  |
| 3 | N = roster menos eliminados (offline) | N correcto sin contar conectados |  |
| 4 | Reducir a 3 vivos (N<4) | Efectos de fase suspendidos; bossbar/calendario/desbloqueos siguen |  |
| 5 | Volver a 4 vivos | Reanudación de efectos |  |
| 6 | Bossbar muestra acto, día, vivos N/M | Formato Letumcraft · Acto ... · Día X · Vivos N/M |  |

### 1.3 Gates

| Paso | Acción | Esperado | Resultado [ ] |
|---|---|---|---|
| 1 | Gate automático con M=16, gate-percent 40 | max(2, ceil(16*40/100)=7, minimum-online-players=15) -> 15 domina |  |
| 2 | Gate con M pequeño | max(2, ceil(M*percent/100), minimum configured) |  |
| 3 | Prueba manual /pd evento iniciar, /pd caravana iniciar | Omite gate |  |

Nota: caso pequeño esperado 2 solo si en copia de TEST se cambia minimum-online-players=2 y M<=5; es cambio de TEST, no nueva configuración de producción. Preparación: tener >=4 UUID vivos en roster para efectos de fase, aunque las pruebas usen 2 clientes y el resto offline; roster NO online.

## 2. Bloque 2 — Sacrificios y Banco Eclipse

### 2.1 Sacrificios irreversibles y ventaja+coste

| Paso | Acción | Esperado | Resultado [ ] |
|---|---|---|---|
| 1 | /pd altar (acto II) abre menú | 7 opciones visibles |  |
| 2 | Elegir una opción y confirmar (doble clic) | Elección persistente |  |
| 3 | Cerrar menú sin confirmar | No cobra |  |
| 4 | Morir/revivir/reiniciar | Elección sigue (irreversible) |  |
| 5 | /pd sacrificios | Lista pública de elecciones |  |
| 6 | Verificar ventaja y coste de cada opción | Abrigo/Cama/Ojo/Pulmón/Estómago/Corazón/Ancla según plan |  |
| 7 | Ancla: reaparece en punto de muerte una vez; grupo paga 1 Núcleo; no concede vuelta gratis ni salta La Fosa | Comportamiento correcto |  |

### 2.2 Banco: 12 / 8 / 30, sol reserved, extensión 43 durable reload

| Paso | Acción | Esperado | Resultado [ ] |
|---|---|---|---|
| 1 | /pd banco | Estado del fondo |  |
| 2 | /pd banco depositar junto al altar | Deposita Núcleos PDC auténticos; sin duplicación |  |
| 3 | /pd banco comprar extension (primera vez) | Staging con tx id; repetir para confirmar |  |
| 4 | Confirmar compra extensión (12 Núcleos) | Extensión única +3d, tope 45; rechaza 44+3>45 sin cobrar |  |
| 5 | /pd banco comprar ritual (8 Núcleos) | Abre ritual global persistente |  |
| 6 | /pd banco comprar sol (30 Núcleos) | Reserva/pago del Sol en escrow; /pd banco sol confirmar <id> / cancelar <id> |  |
| 7 | Reiniciar servidor tras extensión | Fecha real sigue 43 (durable) |  |
| 8 | /pd temporada y /pd actos con base 40 + extensión | Final día 43; sin anuncio de final el día 40 |  |

Debug items: activar debug-commands: true + permiso permadeath.debug solo en copia; probar /pd nucleo, esquirla, sello, mascara, cartucho, filtro; regresar debug-commands: false al acabar.

## 3. Bloque 3 — La Fosa y Chispas

### 3.1 Run 10–15 min, 1/día real, tope 6

| Paso | Acción | Esperado | Resultado [ ] |
|---|---|---|---|
| 1 | /pd fosa empezar | Inicia run 10–15 min en recinto Reserva |  |
| 2 | Completar run | Gana 1 Chispa |  |
| 3 | Intentar segundo run mismo día real | Denegado (tope 1/día real) |  |
| 4 | Esperar día real siguiente | Permite nuevo run |  |
| 5 | Acumular hasta 6 Chispas por UUID | Tope 6 por temporada |  |
| 6 | /pd fosa estado | Estado del run/Chispas |  |

No usar /pd dia para acelerar el ledger diario: no promete acelerar época.

### 3.2 Costes por vuelta y caminos por roster

| Paso | Acción | Esperado | Resultado [ ] |
|---|---|---|---|
| 1 | Vuelta 1 (N>=3 vivos) | Coste 1 Chispa; ritual completo (2 vivos + Sello) |  |
| 2 | Vuelta 2 | Coste 2 Chispas |  |
| 3 | Vuelta 3 | Coste 3 Chispas |  |
| 4 | Intentar 4ª vuelta | Denegado (máx 3) |  |
| 5 | N=2 vivos | Ritual mínimo (1 vivo + Sello), coste k Chispas (siguiente vuelta), no según N vivos |  |
| 6 | N<=1 vivo | Altar Fosa sin vivos, coste k Chispas; Válvula 5d SIN Núcleo peaje; Núcleo 1 solo camino <=1 vivo |  |

N = vivos del roster; k = número de vuelta (1/2/3). Coste en Chispas = k, no N.

### 3.3 Válvula 5 días y force admin

| Paso | Acción | Esperado | Resultado [ ] |
|---|---|---|---|
| 1 | Muerto con Chispas suficientes para siguiente coste, 5 días reales muerto | Vuelta solitaria permitida aunque haya vivos |  |
| 2 | Último vivo muere -> Fosa -> puede volver solo | Permitido |  |
| 3 | 0 vivos Y nadie conectado (puede haber último muerto en Fosa) | Fin de temporada con estadísticas |  |
| 4 | Muerto abandona | Su sitio sigue |  |
| 5 | /pd revivirforzado <jugador> (permiso permadeath.admin.forcerevive) | No consume Chispas ni vuelta ni contador; log administrativo |  |
| 6 | /pd revivir <jugador> en temporada | Pasa por Fosa/policy; consume Chispas N + contador solo en éxito |  |

/pd revivirforzado no modifica CONTADOR revivals ni Chispas, pero SÍ puede actualizar historial/log administrativo revived. Preservar historial, no borrarlo.

## 4. Bloque 4 — Último Día (EN REVISIÓN)

B4 está en revisión. Los casos siguientes se PRUEBAN, no se presupone éxito. No declarar verificado.

> Escenarios de victoria/derrota/extinción: usar COPIAS independientes desde backup PRE-final con servidor apagado. No resetear seasonId de producción, no borrar archivos reales ni inventar comando reset.

### 4.1 Final día 40 o 43

| Paso | Acción | Esperado | Resultado [ ] |
|---|---|---|---|
| 1 | Base 40 días, sin extensión | Final el día 40 |  |
| 2 | Base 40 + extensión comprada | Final el día 43; sin anuncio de final el día 40 |  |
| 3 | /pd temporada / /pd actos | Muestran fin real y actor final |  |
| 4 | Al día final con 30 Núcleos en Banco | Enciende el Sol (victoria) |  |
| 5 | Al día final sin 30 Núcleos | Derrota |  |
| 6 | Repetir pago tras reinicio | No cobra dos veces (tx idempotente finale-sun:<seasonId>:<día>) |  |

### 4.2 ENDED y estadísticas

| Paso | Acción | Esperado | Resultado [ ] |
|---|---|---|---|
| 1 | Tras final (ENDED) | Nuevos runs/compras/revives/ranking denegados |  |
| 2 | /pd fosa volver / /pd revivir tras final | Denegados |  |
| 3 | Roster (incluidos muertos) entra a ver final | Sin patadas |  |
| 4 | /pd final | Pantalla con días sobrevividos, muertes por UUID, quién llegó vivo, núcleos, eventos, sacrificios |  |
| 5 | Stats sobreviven al revive | Nombres + días correctos |  |
| 6 | Extinción temprana por 0 vivos | Fin con estadísticas |  |

### 4.3 Reinicio/kill -9 en borde del final

| Paso | Acción | Esperado | Resultado [ ] |
|---|---|---|---|
| 1 | Reiniciar en borde del final | Reconcilia por mismo tx; sin doble cobro |  |
| 2 | /pd dia adelante/atrás | No duplica ni esconde el fin |  |

## 5. Cierre de prueba

- Regresar debug-commands: false en config de la copia.
- Quitar permiso permadeath.debug de jugadores de prueba si se otorgó.
- Conservar backup y logs para reporte.
- No declarar verificado en producción; reportar resultados con datos de la sección 0.
- B4 sigue en revisión: reportar hallazgos, no asumir éxito.