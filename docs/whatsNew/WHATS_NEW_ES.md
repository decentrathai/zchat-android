# Changelog
All notable changes to this application will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this application adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Supported section titles:
- Añadido, Cambiado, Corregido, Removido

**Please be aware that this changelog primarily focuses on user-related modifications, emphasizing changes that can
directly impact users rather than highlighting other key architectural updates.**

## [Unreleased]

## [2.13.0 (21300)] - 2026-09-22

### Cambiado:
- Por qué tenías que actualizar. Zcash actualizó su red dos veces este año (28 de julio y 6 de noviembre de 2026). Las versiones anteriores de ZChat no pueden enviar en ella ni ver lo que llega. Esta versión sí.
- Tu saldo se está poniendo al día. ZChat está volviendo a leer la blockchain para ponerse al día con las dos actualizaciones. Hasta que termine, puede que tu saldo y algunos mensajes aparezcan incompletos. No se pierde nada de lo que te enviaron por la blockchain, salvo en los chats con mensajes temporales activados. Mantén ZChat abierto, mejor con Wi-Fi. El progreso se muestra arriba en tu lista de chats.
- Tu ZEC anterior. Sigue siendo tuyo. La primera vez que lo gastes, puede que ZChat te pida moverlo una vez. Ese movimiento hace público el monto, y ZChat te lo explica antes de que confirmes.
- Amigos que aún no han actualizado. Los mensajes Shielded que envíes a un amigo con la versión 2.12.4 o anterior no le aparecerán hasta que actualice. No se pierde nada. Los chats Shielded muestran un recordatorio. Los mensajes Open pasan por relays, y la app explica qué pueden ver los relays.
- Direcciones. La pantalla Recibir de la billetera muestra una dirección nueva cada vez. Todas llegan a tu billetera. Tu dirección de ZChat (Mi dirección) no ha cambiado.
- Equipo de ZChat. El equipo no tiene acceso a la antigua dirección del Equipo de ZChat, así que los mensajes enviados a ella no se podían leer. Ese contacto ahora se llama “Equipo de ZChat (dirección antigua, no supervisada)”. Para pedir ayuda, usa Más → Envíanos tus Comentarios.
- Si algo no se ve bien. No desinstales ZChat ni crees una billetera nueva. Al desinstalar se borran tus chats, y tu ZEC solo se recupera con tu frase de recuperación. Usa Más → Envíanos tus Comentarios o visita zsend.xyz/update.

## [2.11.0 (62)] - 2026-04-13

### Añadido:
- Cifrado de ratchet simétrico extremo a extremo — los mensajes ahora usan una capa de ratchet determinista con secreto hacia adelante sobre el transporte de memos de Zcash. La restauración desde semilla se conserva: la raíz del ratchet se deriva de la semilla más los IDs de transacción KEX en cadena.
- Compartición de archivos cifrada — comparte imágenes directamente en el chat. Los archivos se cifran con AES-256-GCM, se suben mediante relés NIP-96 y Blossom, y se muestran en línea con visor de pantalla completa y placeholders Blurhash.
- Quantum Shield — clave pre-compartida opcional de 32 bytes intercambiada mediante código QR y mezclada en la raíz del ratchet. Añade cobertura post-cuántica contra adversarios de cosechar ahora y descifrar después.
- Verificación de número de seguridad — huella digital de 32 hex derivada de SHA-256 de las claves públicas del par ordenadas. El icono de escudo en el encabezado del chat abre un diálogo para comparación fuera de banda.
- Banner de cambio de clave — advertencia magenta cuando la clave pública de un par cambia durante el intercambio.
- Diálogo de información de seguridad en el menú Más listando las protecciones que ZCHAT proporciona y las limitaciones conocidas.
- Indicador de progreso de subida de imagen con etiquetas de etapa durante el envío.
- Diálogo de confirmación al eliminar un mensaje que explica que solo se oculta localmente.

### Corregido:
- Fuga de InputStream en el selector de imágenes cerrada con .use para limpieza garantizada.
- Manejo de cancelación en la subida de imagen — la cancelación del scope ya no aparece como un toast espurio de Upload failed.
- El estado de envío ya no se queda atascado en Sending tras una cancelación de subida.
- Guardia contra subidas concurrentes rechaza un segundo tap de imagen mientras hay una subida en progreso.
- Memoria de bitmap acotada por imagen decodificada mediante submuestreo inSampleSize para prevenir OOM en chats largos con muchas imágenes.
- Las cargas E2E1 malformadas ahora aparecen como el placeholder Mensaje cifrado (no se puede descifrar) en lugar de mostrar bytes cifrados como texto del mensaje.
- Primera pipeline de pruebas JVM unitarias con 103 pruebas pasando cubriendo el ratchet, formato de wire, compartición de archivos, muestreo y máquina de estado Quantum Shield.
- Pulido del tema Nightwire — las pantallas Recibir, Solicitar y QR ahora se renderizan en cian en lugar de púrpura.

## [2.8.8 (3)] - 2026-02-20

### Corregido:
- Corregido el fallo de FileProvider en variantes Store y Testnet.
- Corregido el fallo en cuentas de hardware wallet Keystone.
- Corregida la condición de carrera en el servicio de notificaciones.
- Corregida la corrupción de mensajes con emoji y caracteres multi-byte.
- Corregida la persistencia de datos de SharedPreferences al terminar la app.
- Corregido el consumo de batería por WakeLock retenido tras la sincronización.
- Corregidos los fallos del servicio de notificaciones por excepciones no controladas.

### Añadido:
- Reglas ProGuard para las bibliotecas Tink crypto y Ktor HTTP.
- Auditoría completa de código en 3 ciclos con todos los hallazgos resueltos.

## [2.8.7 (2)] - 2026-02-19

### Corregido:
- Corregido el cálculo de Enviar Todo — el destinatario ahora recibe ~96% del saldo.
- Corregido el parpadeo del campo de texto de monto personalizado.
- Corregido el sonido de notificación que no reproducía.
- Corregida la notificación de sincronización en la pantalla de bloqueo.

### Cambiado:
- Reemplazada la opción Propina Media con Enviar Todo en la selección de monto.
- Después de enviar el primer mensaje, navegar directamente a la conversación.
- Mostrar saldo disponible en la pantalla de composición y diálogo de monto.

## [2.8.6 (1)] - 2026-02-18

### Corregido:
- Corregida la deduplicación de mensajes para mensajes multi-fragmento.
- Corregida la detección de cambio pendiente para errores de fondos insuficientes.
- Corregida la seguridad de hilos del ID de conversación.
- Corregida la inconsistencia de visualización de monto cero.

## [2.8.5 (1)] - 2026-02-17

### Añadido:
- Sistema completo de notificaciones con sonido y vibración personalizados.
- Sincronización en segundo plano cada 15 minutos.
- Banners de notificación dentro de la app.
- Privacidad de notificaciones en pantalla de bloqueo.
- Pantalla de configuración de notificaciones.
- Soporte de silenciar por conversación.

## [2.8.3 (1)] - 2026-02-15

### Añadido:
- Protocolo ZMSG v4 con IDs de conversación.
- Fragmentación de mensajes para mensajes mayores a 512 bytes.
- Cifrado de extremo a extremo con intercambio de claves autenticado.
- Mensajería grupal con distribución de claves ECIES.
- Reacciones, confirmaciones de lectura y respuestas con hilo.
- Mensajes con bloqueo temporal.
- Solicitudes de pago en el chat.
- Libreta de contactos con alias.
- Tema cyberpunk con fuentes personalizadas.
