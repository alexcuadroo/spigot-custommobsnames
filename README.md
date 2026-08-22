# Custom Mobs Names - Plugin para Paper 26.1

Plugin para servidores **Paper 26.1+** (Minecraft 26.1.2, Java 25) que pone un
**nombre personalizado encima de las criaturas**, con soporte de **colores**.

## Créditos

Este proyecto es un derivado de
[WazuStudio/spigot-custommobsnames](https://github.com/WazuStudio/spigot-custommobsnames).
El crédito por la implementación original corresponde a **WazuStudio**.

## Características

- Lista de nombres por criatura en `config.yml` (se elige uno al azar).
- Colores clásicos (`&c`, `&a`, `&l`...) y **MiniMessage** (`<red>`, `<gradient:...>`).
- Nombres visibles encima del mob (`visibility` activada automáticamente).
- Se aplica al **spawnear** y al **cargar chunks** (también a mobs ya existentes al recargar).
- Mundos deshabilitados opcionales.

## Compilar

Requiere **Java 25** y **Maven**:

```
mvn clean package
```

El jar queda en `target/CustomMobsNames-1.0.0.jar`. Guárdalo en la carpeta `plugins/`
del servidor y reinicia (o ejecuta `/custommobsnames reload` con una
configuración ya guardada).

## Configuración (`config.yml`)

```yaml
mobs:
  # Cerdo -> "Pedro" en rojo
  pig:
    custom-names:
    - '&cPedro'

  # Varias opciones por criatura
  zombie:
    custom-names:
    - '&aZombie'
    - '&cZombie &lÉLITE'
```

- **Clave**: id de la criatura en minúsculas (`pig`, `cow`, `zombie`, `creeper`,
  `mooshroom`, ...). También acepta nombres antiguos (`mushroom_cow`, `snowman`,
  `pig_zombie`). Incluye la criatura nueva de 26.1: `mannequin`.
- **`custom-names`**: lista de nombres; cada criatura recibe uno aleatorio.
- **`force-change`** (opcional, default `false`): renombra mobs que ya tenían
  nombre (cuidado con las mascotas).
- **`description`** (opcional, solo mannequins): texto que aparece debajo del
  nombre. Si no se pone, el plugin **oculta la etiqueta "NPC"** que los mannequins
  muestran por defecto. Ejemplo:

  ```yaml
  mobs:
    mannequin:
      custom-names:
      - '&dVendedor'
      description: '&7Tienda de pociones'
  ```

### Actualizaciones

Al iniciar, el plugin consulta en segundo plano la última release pública de
[GitHub](https://github.com/WazuStudio/spigot-custommobsnames/releases). Si hay
una versión nueva, la consola muestra la versión instalada, la nueva y el enlace
de descarga. La comprobación tiene un límite de cinco segundos y nunca bloquea el
hilo del servidor.

### Añadir sonidos nuevos

Esta versión aún no reproduce ni reemplaza sonidos: su única función es mostrar
nombres y descripciones de mobs. Por eso **no hay una clave `sounds` en
`config.yml`** que puedas añadir hoy.

Para añadir una nueva opción de sonidos al plugin en una futura versión, habrá
que implementar primero su reproducción en Java y después exponerla en la
configuración. Para sonidos propios de un resource pack, el flujo de Minecraft
es este:

1. Crea o convierte el audio a `.ogg`.
2. Añádelo al pack, por ejemplo en
   `assets/tu_namespace/sounds/mobs/mi_sonido.ogg`.
3. Decláralo en `assets/tu_namespace/sounds.json` con una clave como
   `"mobs.mi_sonido"`.
4. Distribuye y activa el resource pack para los jugadores.
5. Cuando el plugin tenga soporte de sonidos, configura esa misma clave de
   sonido (`tu_namespace:mobs.mi_sonido`) en la opción que se documente para la
   versión correspondiente.

No copies archivos `.ogg` en la carpeta del plugin: Minecraft solo reconoce
sonidos personalizados a través de un resource pack enviado al cliente.

### Códigos de color

| Código | Color    | Código | Color     |
|--------|----------|--------|-----------|
| `&c`   | rojo     | `&a`   | verde     |
| `&e`   | amarillo | `&6`   | dorado    |
| `&9`   | azul     | `&b`   | azul claro|
| `&d`   | rosa     | `&5`   | morado    |
| `&f`   | blanco   | `&7`   | gris      |
| `&l`   | negrita  | `&o`   | cursiva   |
| `&n`   | subrayado| `&r`   | reset     |

Si usas `<tag>` en un nombre se interpreta como **MiniMessage**.

## Comandos

- `/custommobsnames reload` – recarga `config.yml` sin reiniciar.

## Permisos

- `custommobsnames.reload` – permite usar `/custommobsnames reload` (default: op).

## Notas técnicas / cambios respecto a la v1.x

- Construido contra **Paper API 26.1.2** con **Java 25** (`maven.compiler.release=25`).
- `api-version: '26.1'` en `plugin.yml`.
- Nombres con la **Adventure API** (`customName(Component)`): necesario porque el
  serializador legacy de `setCustomName(String)` quedó deprecado/obsoleto en 26.1.
- Al spawnear, el nombre se aplica en `EntitySpawnEvent` y **se re-aplica 1 tick
  después** (via `EntityScheduler`) para garantizar que el nameplate llega a todos
  los clientes aunque la metadata del spawn ya se esté construyendo.
- Los **mannequins** muestran "NPC" bajo el nombre desde el snapshot 25w36b
  (comportamiento vanilla). El plugin lo oculta activando el flag vanilla
  `hide_description` (vía `Mannequin#setDescription(null)`), o muestra el texto
  configurado con la clave `description`.
- Se eliminó la lista fija de tipos: ahora **cualquier** criatura configurada
  funciona, incluidas las añadidas después de 1.13 (allay, axolotl, warden,
  sniffer, breeze, creaking, happy_ghast...).
- Soporta configuraciones antiguas con claves como `mushroom_cow`, `snowman`,
  `pig_zombie`.

## Licencia

MIT - ver [LICENSE](LICENSE).
