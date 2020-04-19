package com.github.quillraven.darkmatter.screen

import com.badlogic.ashley.core.PooledEngine
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.utils.viewport.FitViewport
import com.github.quillraven.darkmatter.Game
import com.github.quillraven.darkmatter.UNIT_SCALE
import com.github.quillraven.darkmatter.V_HEIGHT
import com.github.quillraven.darkmatter.V_WIDTH
import com.github.quillraven.darkmatter.asset.MusicAsset
import com.github.quillraven.darkmatter.asset.ShaderProgramAsset
import com.github.quillraven.darkmatter.asset.SoundAsset
import com.github.quillraven.darkmatter.asset.TextureAsset
import com.github.quillraven.darkmatter.asset.TextureAtlasAsset
import com.github.quillraven.darkmatter.audio.AudioService
import com.github.quillraven.darkmatter.ecs.component.AnimationComponent
import com.github.quillraven.darkmatter.ecs.component.AnimationType
import com.github.quillraven.darkmatter.ecs.component.AttachComponent
import com.github.quillraven.darkmatter.ecs.component.FacingComponent
import com.github.quillraven.darkmatter.ecs.component.GraphicComponent
import com.github.quillraven.darkmatter.ecs.component.MoveComponent
import com.github.quillraven.darkmatter.ecs.component.PlayerComponent
import com.github.quillraven.darkmatter.ecs.component.TransformComponent
import com.github.quillraven.darkmatter.ecs.system.AnimationSystem
import com.github.quillraven.darkmatter.ecs.system.AttachSystem
import com.github.quillraven.darkmatter.ecs.system.CameraShakeSystem
import com.github.quillraven.darkmatter.ecs.system.DAMAGE_AREA_HEIGHT
import com.github.quillraven.darkmatter.ecs.system.DamageSystem
import com.github.quillraven.darkmatter.ecs.system.DebugSystem
import com.github.quillraven.darkmatter.ecs.system.MoveSystem
import com.github.quillraven.darkmatter.ecs.system.PlayerAnimationSystem
import com.github.quillraven.darkmatter.ecs.system.PlayerColorSystem
import com.github.quillraven.darkmatter.ecs.system.PlayerInputSystem
import com.github.quillraven.darkmatter.ecs.system.PowerUpSystem
import com.github.quillraven.darkmatter.ecs.system.RemoveSystem
import com.github.quillraven.darkmatter.ecs.system.RenderSystem
import com.github.quillraven.darkmatter.event.GameEvent
import com.github.quillraven.darkmatter.event.GameEventListener
import com.github.quillraven.darkmatter.event.GameEventManager
import com.github.quillraven.darkmatter.event.GameEventType
import kotlinx.coroutines.launch
import ktx.app.KtxScreen
import ktx.ashley.entity
import ktx.assets.async.AssetStorage
import ktx.async.KtxAsync
import ktx.log.logger
import ktx.math.vec2
import kotlin.math.min

private val LOG = logger<GameScreen>()
private const val MAX_DELTA_TIME = 1 / 30f
private const val PLAYER_START_SPEED = 3f
private const val SHIP_FIRE_OFFSET_X = 1f // in pixels
private const val SHIP_FIRE_OFFSET_Y = -6f // in pixels

class GameScreen(
    private val game: Game,
    private val assets: AssetStorage = game.assets,
    private val gameEventManager: GameEventManager = game.gameEventManager,
    private val stage: Stage = game.stage,
    private val audioService: AudioService = game.audioService
) : KtxScreen, GameEventListener {
    private val viewport = FitViewport(V_WIDTH.toFloat(), V_HEIGHT.toFloat())
    private val playerGraphicSize = vec2()
    private val engine = PooledEngine().apply {
        val atlas = assets[TextureAtlasAsset.GRAPHICS.descriptor]
        val playerGraphicRegion = atlas.findRegion("ship_base")
        playerGraphicSize.set(playerGraphicRegion.originalWidth.toFloat(), playerGraphicRegion.originalHeight.toFloat())

        addSystem(DebugSystem(gameEventManager, audioService))
        addSystem(PowerUpSystem(gameEventManager, audioService))
        addSystem(PlayerInputSystem(viewport))
        addSystem(MoveSystem())
        addSystem(DamageSystem(gameEventManager, audioService))
        addSystem(
            PlayerAnimationSystem(
                playerGraphicRegion,
                atlas.findRegion("ship_left"),
                atlas.findRegion("ship_right")
            )
        )
        addSystem(AttachSystem())
        addSystem(AnimationSystem(atlas))
        addSystem(CameraShakeSystem(viewport.camera, gameEventManager))
        addSystem(PlayerColorSystem(gameEventManager))
        addSystem(
            RenderSystem(
                stage,
                assets[ShaderProgramAsset.OUTLINE.descriptor],
                viewport,
                gameEventManager,
                assets[TextureAsset.BACKGROUND.descriptor]
            )
        )
        addSystem(RemoveSystem(gameEventManager))
    }
    private var respawn = true

    override fun show() {
        LOG.debug { "Show" }
        gameEventManager.addListener(GameEventType.PLAYER_SPAWN, this)
        gameEventManager.addListener(GameEventType.PLAYER_DEATH, this)
        spawnDarkMatter()

        val old = System.currentTimeMillis()
        val music = assets.loadAsync(MusicAsset.GAME.descriptor)
        KtxAsync.launch {
            music.join()
            LOG.debug { "It took ${(System.currentTimeMillis() - old) * 0.001f} seconds to load the game music" }
            audioService.play(MusicAsset.GAME)
        }
    }

    override fun hide() {
        LOG.debug { "Hide" }
        gameEventManager.removeListener(this)
        KtxAsync.launch {
            assets.unload(MusicAsset.GAME.descriptor)
        }
        engine.removeAllEntities()
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
        stage.viewport.update(width, height, true)
    }

    override fun render(delta: Float) {
        if (respawn) {
            spawnPlayer()
        }
        val deltaTime = min(delta, MAX_DELTA_TIME)
        engine.update(deltaTime)
        audioService.update()
    }

    override fun dispose() {
        LOG.debug { "Dispose" }
        LOG.debug { "Number of entities: ${engine.entities.size()}" }
    }

    private fun spawnPlayer() {
        respawn = false

        // ship
        val ship = engine.entity {
            with<PlayerComponent>()
            with<FacingComponent>()
            with<MoveComponent> {
                speed.y = PLAYER_START_SPEED
            }
            with<TransformComponent> {
                setInitialPosition(V_WIDTH * 0.5f - size.x * 0.5f, V_HEIGHT * 0.5f - size.y * 0.5f, 1f)
                size.set(playerGraphicSize.x * UNIT_SCALE, playerGraphicSize.y * UNIT_SCALE)
            }
            with<GraphicComponent>()
        }

        // fire effect of ship
        engine.entity {
            with<TransformComponent>()
            with<AttachComponent> {
                entity = ship
                offset.set(SHIP_FIRE_OFFSET_X * UNIT_SCALE, SHIP_FIRE_OFFSET_Y * UNIT_SCALE)
            }
            with<GraphicComponent>()
            with<AnimationComponent> {
                type = AnimationType.FIRE
            }
        }

        // remove any power ups and reset the spawn timer
        engine.getSystem(PowerUpSystem::class.java).reset()

        audioService.play(SoundAsset.SPAWN)
        gameEventManager.dispatchEvent(GameEventType.PLAYER_SPAWN)
    }

    private fun spawnDarkMatter() {
        engine.entity {
            with<TransformComponent> {
                size.set(V_WIDTH.toFloat(), DAMAGE_AREA_HEIGHT)
            }
            with<AnimationComponent> {
                type = AnimationType.DARK_MATTER
            }
            with<GraphicComponent>()
        }
    }

    override fun onEvent(type: GameEventType, data: GameEvent?) {
        when (type) {
            GameEventType.PLAYER_SPAWN -> LOG.debug { "Spawn new player" }
            GameEventType.PLAYER_DEATH -> {
                LOG.debug { "Player died with a distance of $data" }
                game.setScreen<GameOverScreen>()
                respawn = true
            }
            else -> {
                // ignore
            }
        }
    }
}