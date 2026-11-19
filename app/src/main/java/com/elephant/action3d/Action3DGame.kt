package com.elephant.action3d

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g3d.*
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Array

class Action3DGame : ApplicationAdapter() {

    private lateinit var camera: PerspectiveCamera
    private lateinit var modelBatch: ModelBatch
    private lateinit var environment: Environment
    private lateinit var modelBuilder: ModelBuilder

    private lateinit var floorModel: Model
    private lateinit var playerModel: Model
    private lateinit var enemyModel: Model
    private lateinit var bulletModel: Model
    private lateinit var powerUpModel: Model

    private lateinit var floorInstance: ModelInstance
    private lateinit var playerInstance: ModelInstance

    private val playerPos = Vector3(0f, 0.5f, 0f)
    var playerHealth = 100
        private set
    var score = 0
        private set
    var wave = 1
        private set
    var isGameOver = false
        private set

    private val enemies = Array<Enemy>()
    private val bullets = Array<Bullet>()
    private val powerUps = Array<PowerUp>()

    private var shootTimer = 0f
    private val shootInterval = 0.18f

    private lateinit var uiBatch: SpriteBatch
    private lateinit var font: BitmapFont

    private val joystickCenter = Vector2()
    private val joystickCurrent = Vector2()
    private var isTouchingJoystick = false
    private var joystickPointer = -1

    // Reusable temp vectors to avoid object allocations in render loop
    private val tmpVec3_1 = Vector3()
    private val tmpVec3_2 = Vector3()
    private val tmpVec2_1 = Vector2()
    private val moveDir = Vector3()

    data class Enemy(val instance: ModelInstance, val position: Vector3, var health: Int, val speed: Float)
    data class Bullet(val instance: ModelInstance, val position: Vector3, val velocity: Vector3, var lifeTime: Float = 2.5f)
    data class PowerUp(val instance: ModelInstance, val position: Vector3, val type: Int)

    override fun create() {
        camera = PerspectiveCamera(67f, Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat()).apply {
            position.set(0f, 14f, 12f)
            lookAt(0f, 0f, 0f)
            near = 1f
            far = 300f
            update()
        }

        modelBatch = ModelBatch()
        modelBuilder = ModelBuilder()

        environment = Environment().apply {
            set(ColorAttribute(ColorAttribute.AmbientLight, 0.5f, 0.5f, 0.5f, 1f))
            add(DirectionalLight().set(0.9f, 0.9f, 0.9f, -1f, -1.2f, -0.4f))
        }

        floorModel = modelBuilder.createBox(40f, 0.2f, 40f, 
            Material(ColorAttribute.createDiffuse(Color.DARK_GRAY)), 
            (Usage.Position or Usage.Normal).toLong())

        playerModel = modelBuilder.createBox(1.2f, 1.2f, 1.2f, 
            Material(ColorAttribute.createDiffuse(Color.CYAN)), 
            (Usage.Position or Usage.Normal).toLong())

        enemyModel = modelBuilder.createSphere(1.2f, 1.2f, 1.2f, 16, 16, 
            Material(ColorAttribute.createDiffuse(Color.FIREBRICK)), 
            (Usage.Position or Usage.Normal).toLong())

        bulletModel = modelBuilder.createSphere(0.4f, 0.4f, 0.4f, 8, 8, 
            Material(ColorAttribute.createDiffuse(Color.GOLD)), 
            (Usage.Position or Usage.Normal).toLong())

        powerUpModel = modelBuilder.createBox(0.8f, 0.8f, 0.8f, 
            Material(ColorAttribute.createDiffuse(Color.LIME)), 
            (Usage.Position or Usage.Normal).toLong())

        floorInstance = ModelInstance(floorModel).apply { transform.setToTranslation(0f, -0.1f, 0f) }
        playerInstance = ModelInstance(playerModel).apply { transform.setToTranslation(playerPos) }

        uiBatch = SpriteBatch()
        font = BitmapFont().apply { data.setScale(2.5f) }

        spawnWave()
    }

    override fun render() {
        val delta = Gdx.graphics.deltaTime
        handleInput(delta)

        if (!isGameOver) {
            updateGame(delta)
        }

        Gdx.gl.glViewport(0, 0, Gdx.graphics.width, Gdx.graphics.height)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)

        modelBatch.begin(camera)
        modelBatch.render(floorInstance, environment)
        modelBatch.render(playerInstance, environment)

        for (i in 0 until enemies.size) modelBatch.render(enemies[i].instance, environment)
        for (i in 0 until bullets.size) modelBatch.render(bullets[i].instance, environment)
        for (i in 0 until powerUps.size) modelBatch.render(powerUps[i].instance, environment)

        modelBatch.end()

        uiBatch.begin()
        if (isGameOver) {
            font.color = Color.RED
            font.draw(uiBatch, "GAME OVER!", Gdx.graphics.width * 0.35f, Gdx.graphics.height * 0.6f)
            font.color = Color.WHITE
            font.draw(uiBatch, "Score: $score | Wave: $wave", Gdx.graphics.width * 0.3f, Gdx.graphics.height * 0.5f)
            font.draw(uiBatch, "Tap Screen to Restart", Gdx.graphics.width * 0.28f, Gdx.graphics.height * 0.4f)
        } else {
            font.color = Color.GREEN
            font.draw(uiBatch, "HP: $playerHealth", 40f, Gdx.graphics.height - 40f)
            font.color = Color.GOLD
            font.draw(uiBatch, "SCORE: $score", 40f, Gdx.graphics.height - 90f)
            font.color = Color.ORANGE
            font.draw(uiBatch, "WAVE: $wave", 40f, Gdx.graphics.height - 140f)
        }
        uiBatch.end()
    }

    private fun handleInput(delta: Float) {
        if (isGameOver) {
            if (Gdx.input.justTouched()) restartGame()
            return
        }

        moveDir.setZero()

        for (i in 0 until 5) {
            if (Gdx.input.isTouched(i)) {
                val x = Gdx.input.getX(i).toFloat()
                val y = Gdx.input.getY(i).toFloat()

                if (x < Gdx.graphics.width / 2f) {
                    if (!isTouchingJoystick) {
                        isTouchingJoystick = true
                        joystickPointer = i
                        joystickCenter.set(x, y)
                    }
                    if (joystickPointer == i) {
                        joystickCurrent.set(x, y)
                        tmpVec2_1.set(joystickCurrent).sub(joystickCenter)
                        if (tmpVec2_1.len2() > 100f) {
                            tmpVec2_1.nor()
                            moveDir.set(tmpVec2_1.x, 0f, tmpVec2_1.y)
                        }
                    }
                } else {
                    shootTimer += delta
                    if (shootTimer >= shootInterval) {
                        shootTimer = 0f
                        shootAtTarget(x, y)
                    }
                }
            } else if (joystickPointer == i) {
                isTouchingJoystick = false
                joystickPointer = -1
            }
        }

        val moveSpeed = 10f
        playerPos.add(moveDir.x * moveSpeed * delta, 0f, moveDir.z * moveSpeed * delta)
        playerPos.x = MathUtils.clamp(playerPos.x, -18f, 18f)
        playerPos.z = MathUtils.clamp(playerPos.z, -18f, 18f)

        playerInstance.transform.setToTranslation(playerPos)

        camera.position.set(playerPos.x, playerPos.y + 14f, playerPos.z + 12f)
        camera.lookAt(playerPos)
        camera.update()
    }

    private fun shootAtTarget(touchX: Float, touchY: Float) {
        val ray = camera.getPickRay(touchX, touchY)
        if (MathUtils.isZero(ray.direction.y)) return
        val distance = -ray.origin.y / ray.direction.y
        if (distance < 0f) return

        val target = tmpVec3_1.set(ray.direction).scl(distance).add(ray.origin)
        val bulletDir = tmpVec3_2.set(target).sub(playerPos).apply { y = 0f }
        if (bulletDir.isZero) return
        bulletDir.nor()

        val spawnPos = Vector3(playerPos).add(bulletDir.x * 1f, 0f, bulletDir.z * 1f)
        val bulletInst = ModelInstance(bulletModel).apply { transform.setToTranslation(spawnPos) }
        bullets.add(Bullet(bulletInst, spawnPos, Vector3(bulletDir).scl(30f)))
    }

    private fun updateGame(delta: Float) {
        // Safe backward iteration for bullets
        for (i in bullets.size - 1 downTo 0) {
            val b = bullets[i]
            b.position.add(b.velocity.x * delta, 0f, b.velocity.z * delta)
            b.instance.transform.setToTranslation(b.position)
            b.lifeTime -= delta

            if (b.lifeTime <= 0f || b.position.dst2(playerPos) > 2025f) {
                bullets.removeIndex(i)
            }
        }

        // Safe backward iteration for enemies
        for (i in enemies.size - 1 downTo 0) {
            val enemy = enemies[i]
            tmpVec3_1.set(playerPos).sub(enemy.position).apply { y = 0f }
            if (!tmpVec3_1.isZero) tmpVec3_1.nor()

            enemy.position.add(tmpVec3_1.x * enemy.speed * delta, 0f, tmpVec3_1.z * enemy.speed * delta)
            enemy.instance.transform.setToTranslation(enemy.position)

            if (enemy.position.dst2(playerPos) < 1.44f) {
                playerHealth -= 15
                enemies.removeIndex(i)
                if (playerHealth <= 0) {
                    playerHealth = 0
                    isGameOver = true
                }
                continue
            }

            for (j in bullets.size - 1 downTo 0) {
                val bullet = bullets[j]
                if (bullet.position.dst2(enemy.position) < 1.21f) {
                    enemy.health -= 50
                    bullets.removeIndex(j)
                    if (enemy.health <= 0) {
                        score += 150
                        if (MathUtils.randomBoolean(0.25f)) spawnPowerUp(enemy.position)
                        enemies.removeIndex(i)
                        break
                    }
                }
            }
        }

        // Safe backward iteration for powerups
        for (i in powerUps.size - 1 downTo 0) {
            val p = powerUps[i]
            p.instance.transform.rotate(Vector3.Y, 120f * delta)
            if (p.position.dst2(playerPos) < 1.69f) {
                if (p.type == 0) playerHealth = MathUtils.clamp(playerHealth + 35, 0, 100)
                else score += 300
                powerUps.removeIndex(i)
            }
        }

        if (enemies.isEmpty) {
            wave++
            spawnWave()
        }
    }

    private fun spawnWave() {
        val count = calculateEnemyCount(wave)
        for (i in 0 until count) {
            val angle = MathUtils.random(0f, 360f)
            val dist = MathUtils.random(14f, 20f)
            val spawnPos = Vector3(
                playerPos.x + MathUtils.cosDeg(angle) * dist,
                0.6f,
                playerPos.z + MathUtils.sinDeg(angle) * dist
            )
            val inst = ModelInstance(enemyModel).apply { transform.setToTranslation(spawnPos) }
            val speed = MathUtils.random(3.5f, 6.5f) + (wave * 0.2f)
            enemies.add(Enemy(inst, spawnPos, 100, speed))
        }
    }

    private fun spawnPowerUp(pos: Vector3) {
        val pPos = Vector3(pos)
        val inst = ModelInstance(powerUpModel).apply { transform.setToTranslation(pPos) }
        powerUps.add(PowerUp(inst, pPos, MathUtils.random(0, 1)))
    }

    private fun restartGame() {
        playerHealth = 100
        score = 0
        wave = 1
        isGameOver = false
        playerPos.set(0f, 0.5f, 0f)
        enemies.clear()
        bullets.clear()
        powerUps.clear()
        spawnWave()
    }

    fun calculateEnemyCount(currentWave: Int): Int = 4 + currentWave * 3

    override fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
        camera.update()
    }

    override fun dispose() {
        modelBatch.dispose()
        floorModel.dispose()
        playerModel.dispose()
        enemyModel.dispose()
        bulletModel.dispose()
        powerUpModel.dispose()
        uiBatch.dispose()
        font.dispose()
    }
}
