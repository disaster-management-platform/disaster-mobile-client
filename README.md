# Cliente móvil de desastres

App Android en Kotlin y Compose para registrar desastres y consultar su ubicación. Puedes guardar un reporte sin conexión: queda en SQLite con Room y se envía al servidor cuando Android permite ejecutar la sincronización.

## Ejecutar

Abre el proyecto en Android Studio y deja que Gradle descargue las dependencias. El proyecto usa el SDK 37 y un JDK 25 para Gradle, según `gradle/gradle-daemon-jvm.properties`. La app funciona desde Android 7.1, API 25.

Inicia el servicio canónico y el Gateway desde sus repositorios hermanos, en terminales separadas. La API requiere su cadena PostgreSQL externa; el Gateway solo recibe destinos HTTP:

```bash
dotnet run --project ../disaster-backend-offline/DisasterLocation --launch-profile http
ReverseProxy__Clusters__disasters-api__Destinations__local__Address=http://localhost:5156/ dotnet run --project ../disaster-api-gateway/DisasterLocation.Gateway --launch-profile http
```

La dirección por defecto es `http://10.0.2.2:5080/api/`. En el emulador de Android, `10.0.2.2` apunta al computador. `127.0.0.1` apuntaría al propio emulador.

Puedes compilar e instalar desde esta carpeta:

```bash
./gradlew :app:installDebug
```

Para probar en un teléfono físico, conecta ambos equipos a la misma red e inicia el gateway escuchando en esa red:

```bash
dotnet run --project ../disaster-api-gateway/DisasterLocation.Gateway --launch-profile http --urls http://0.0.0.0:5080
```

Luego cambia la dirección al compilar, usando la IP de tu computador:

```bash
./gradlew :app:installDebug -PAPI_BASE_URL=http://192.168.1.100:5080/api/
```

También puedes guardar `API_BASE_URL=http://192.168.1.100:5080/api/` en tu archivo personal `~/.gradle/gradle.properties`. La URL debe terminar en `/api/`; Retrofit agrega `desastres` al hacer la petición. Configura también `AllowedHosts` del Gateway con la IP o el nombre usado por el teléfono. El puerto 5080 debe estar disponible en el firewall. Las compilaciones de desarrollo admiten HTTP local; release rechaza una URL sin HTTPS y su configuración de red no permite HTTP.

El mapa requiere una clave de Google Maps. Agrega `MAPS_API_KEY=tu_clave` a `local.properties`, que no se sube a Git. El formulario y la lista pueden usarse sin cargar el mapa. Las imágenes del mapa necesitan conexión o estar en la caché de Google Maps.

## Qué pasa al guardar

El formulario valida los campos y el repositorio genera un UUID. Guarda el reporte en Room con ese UUID como `clientRequestId` y estado `PENDING`. La confirmación que ves en pantalla significa que el reporte quedó guardado en el teléfono; no espera a que responda el servidor.

Después se programa `SyncDisastersWorker` con WorkManager y una restricción de conectividad. El worker lee hasta 50 pendientes y los envía a POST /api/desastres/batch, conservando clientRequestId y el cuerpo original por operación. Esa clave se crea una sola vez y se conserva durante todos los reintentos, incluso después de cerrar la app.

El formulario permite seleccionar un tipo previamente descargado, una fecha de inicio UTC y una clasificación de atención independiente de la severidad. El catálogo público se guarda en Room y puede consultarse sin conectividad; si todavía no se descargó, se conserva el registro sin tipo y se muestra que está pendiente. Los tipos desactivados mantienen sus referencias históricas.

Radio es opcional: vacío significa desconocido (`null`), sin valores predeterminados. Si se proporciona, debe ser finito, mayor que cero y máximo 1.000.000 metros. El valor se conserva en Room y en cada reintento; el mapa solo dibuja un círculo cuando se conoce.

Los registros históricos conservan su UUID, Radio y estado. No existe información para saber si un pendiente con Radio null fue enviado anteriormente como 500. Se reintenta con el contenido local y la misma clave: si el servidor ya aceptó otro contenido, el 409 queda visible como conflicto para revisión, sin sustituir el Radio ni crear otra operación. Una confirmación con Radio diferente también queda en conflicto.

Cuando la API devuelve el reporte confirmado, se guarda su ID en `serverId` y el estado cambia a `SYNCED`. Se comprueba que el ID sea positivo y que el contenido coincida. Tanto el `201` del primer registro como el `200` de un reintento sirven como confirmación.

Si se pierde una respuesta, el reporte sigue pendiente. El próximo intento lleva el mismo UUID y el servidor recupera el registro anterior, sin duplicarlo.

| Estado en Room | Lo que verás |
| --- | --- |
| `PENDING` | Guardado localmente, pendiente de sincronización. |
| `SYNCED` | Sincronizado, con el ID del servidor. |
| `ERROR` | El servidor rechazó la petición y hace falta revisar el reporte. |
| `CONFLICT` | La clave ya existe en el servidor con otros datos. |

Los problemas de red, `408`, `429` y errores `5xx` conservan el pendiente y se reintentan con espera exponencial, comenzando en 30 segundos. Los demás errores HTTP quedan visibles en la lista; un `400` o `409` no se reintenta sin parar ni genera otra clave automáticamente. Esta versión no incluye una pantalla para corregir esos reportes.

Room versión 3 utiliza migraciones incrementales desde las versiones 1 y 2: conserva UUID, identificadores remotos, Radio, estados y pendientes, sin inventar atributos históricos. Cada nueva creación guarda su cuerpo original para los reintentos. Un recibo POST histórico no reemplaza una versión más reciente obtenida mediante GET. Las descargas se validan completas antes de almacenarse; las respuestas inválidas conservan los datos locales.

La lista, el catálogo y los marcadores leen siempre de Room mediante Flow, StateFlow y collectAsStateWithLifecycle. Al actualizar se descargan los reportes del servidor y se incorporan a la base local sin borrar los pendientes. Si no hay conexión, puedes seguir consultando lo que ya estaba guardado.

La sincronización usa una cadena de trabajo única llamada `sync-disasters`, con `APPEND_OR_REPLACE`: si guardas mientras hay un envío activo, se agrega otro recorrido al terminar. Así el reporte nuevo no se pierde por haber usado `KEEP` justo cuando finalizaba el trabajo anterior. Al iniciar la app también se programa un recorrido para recuperar pendientes que pudieran quedar entre el guardado y la programación.

WorkManager decide el momento de ejecución según la red y las restricciones del sistema; no promete un envío inmediato. Quitar la app de recientes conserva los datos y el trabajo programado. Si usas «Forzar detención», abre la app otra vez para que Android pueda retomar el trabajo. Desinstalar la app o borrar sus datos elimina la base local.

## Dónde está cada parte

- `data/local`: entidad, DAO y base de datos Room. El esquema se exporta a `app/schemas`; futuros cambios deben tener su migración, sin borrar la base.
- `data/repository/DisasterRepository.kt`: valida, guarda localmente y actualiza la caché de consulta.
- `data/sync`: programación, worker y envío de pendientes.
- `data/remote/DisasterApi.kt`: contrato Retrofit, incluida la cabecera de idempotencia.
- `di`: instancias compartidas de Room y Retrofit. La aplicación configura la fábrica de workers de Hilt.
- `ui`: formulario, lista con estados de sincronización y mapa.

## Pruebas

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

Las pruebas usan Room sobre SQLite con Robolectric y un servidor HTTP local de prueba. Cubren el guardado sin HTTP, la reapertura de la base, la conservación del UUID y el cuerpo en los reintentos, respuestas `201/200`, errores permanentes y temporales, confirmaciones inválidas, cancelación y la combinación de reportes locales con consultas al servidor.

Para comprobar el flujo completo en el teléfono:

1. Activa el modo avión y guarda un reporte. Debe aparecer como pendiente en la lista.
2. Cierra y abre la app. El reporte debe seguir ahí.
3. Inicia la API y el gateway, y recupera la conexión. Espera a que WorkManager ejecute el envío; si ya había fallado un intento, puede estar esperando su siguiente reintento.
4. Comprueba que el estado cambie a sincronizado y muestre el ID del servidor.
5. Consulta `http://localhost:5080/api/desastres` desde el computador. Debe haber un solo registro para esa operación.

Documentación de consulta: [Room](https://developer.android.com/training/data-storage/room), [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work) e [integración con Hilt](https://developer.android.com/training/dependency-injection/hilt-jetpack).

La subida procesa los resultados del lote individualmente: Created y AlreadyProcessed requieren una confirmación válida; ValidationError y Conflict son visibles como ERROR y CONFLICT. Resultados ausentes, ambiguos, inválidos o RetryableFailure permanecen PENDING con la misma identidad. Un fallo completo del lote no confirma ninguna operación. La programación conserva un trabajo pendiente, o reserva un recorrido posterior cuando ya hay uno ejecutándose, evitando descargar el catálogo y los desastres una vez por cada registro offline.

Las descargas utilizan GET /api/desastres/sync en páginas de hasta 50. Room guarda cada página y su cursor en la misma transacción; una respuesta inválida o un fallo local no avanza el cursor. Las descargas concurrentes verifican el cursor anterior antes de confirmar y nunca reemplazan versiones más recientes, pendientes, errores o conflictos. Después de 20 páginas el trabajo conserva el progreso y reintenta. La migración 2→3 añade únicamente el estado de sincronización, preservando catálogo, CreationPayload, UUID, Radio, versiones y estados anteriores.
