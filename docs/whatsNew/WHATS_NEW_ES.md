# Changelog
All notable changes to this application will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this application adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Supported section titles:
- Añadido, Cambiado, Corregido, Removido

**Please be aware that this changelog primarily focuses on user-related modifications, emphasizing changes that can
directly impact users rather than highlighting other key architectural updates.**

## [Unreleased]

## [2.11.0 (62)] - 2026-04-13

### Añadido:
- **Cifrado de ratchet simétrico extremo a extremo** — los mensajes ahora usan una capa de ratchet determinista con secreto hacia adelante sobre el transporte de memos de Zcash. La restauración desde semilla se conserva: la raíz del ratchet se deriva de la semilla más los IDs de transacción KEX/KEXACK en cadena.
- **Compartición de archivos cifrada** — comparte imágenes directamente en el chat. Los archivos se cifran con AES-256-GCM con una clave aleatoria por archivo envuelta con el secreto compartido E2E, se suben mediante relés NIP-96 / Blossom, y se muestran en línea en la vista de chat. Incluye visor de pantalla completa y placeholders de baja resolución Blurhash.
- **Quantum Shield** — clave pre-compartida opcional de 32 bytes intercambiada mediante código QR y mezclada en la raíz del ratchet. Añade una cobertura post-cuántica contra adversarios 'cosechar ahora, descifrar después'.
- **Verificación de número de seguridad** — huella digital de 32 hex derivada de SHA-256 de las claves públicas del par ordenadas. El icono de escudo en el encabezado del chat abre un diálogo para comparación fuera de banda con tu contacto.
- **Banner de cambio de clave** — advertencia magenta aparece cuando la clave pública de un par cambia durante el intercambio de claves.
- **Diálogo de información de seguridad** — accesible desde Más → Seguridad. Lista las protecciones que ZCHAT proporciona y las limitaciones conocidas actuales.
- **Indicador de progreso de subida de imagen** — barra de progreso con etiquetas de etapa durante el envío de imagen.
- **Diálogo de confirmación de eliminación de mensaje** — previene ocultamiento accidental de mensajes con una explicación clara.

### Corregido:
- Fuga de InputStream en el selector de imágenes.
- Manejo de cancelación en la subida de imagen — la cancelación del scope ya no aparece como un toast espurio de 'Upload failed'.
- SendMessageState ya no se queda atascado en 'Sending' tras una cancelación de subida.
- Guardia contra subidas concurrentes rechaza un segundo tap de imagen mientras hay una subida en progreso.
- Memoria de bitmap acotada por imagen decodificada mediante submuestreo `inSampleSize` para prevenir OOM en chats largos con muchas imágenes.
- Las cargas `E2E1:` malformadas ahora aparecen como 'Mensaje cifrado (no se puede descifrar)' en lugar de mostrar bytes cifrados como texto del mensaje.
- Primera pipeline de pruebas JVM unitarias con 103 pruebas pasando.

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
