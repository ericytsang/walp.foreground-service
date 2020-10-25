package com.github.ericytsang.foregroundnotification.app.android

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.github.ericytsang.foregroundnotification.app.android.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.Serializable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * this screen will let you start and stop the [ForegroundService].
 */
class MainActivity:AppCompatActivity()
{
    override fun onCreate(savedInstanceState:Bundle?)
    {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater,contentView,false)
        setContentView(binding.root)

        binding.button.setOnClickListener {
            startForegroundServiceCompat(Intent(this,ForegroundService::class.java))
        }
    }
}

val Activity.contentView:ViewGroup get() = findViewById(android.R.id.content)

/**
 * this is a service that will display a notification, that will count how long that the
 * notification has been showing for. it will also change colors every time you tap on it.
 */
class ForegroundService:Service(),CoroutineScope by CoroutineScope(Dispatchers.Main+Job())
{

    private val startTime = System.currentTimeMillis()

    private var notificationBackgroundColor = randomColor()

    sealed class Params:Serializable
    {
        data class ChangeColors(val unused:Int = 0):Params()
        data class StopService(val unused:Int = 0):Params()
    }

    override fun onCreate()
    {
        super.onCreate()

        // display notification for foreground service
        val notificationFactoryProduct = NotificationFactory.getNotification(
            this,notificationBackgroundColor,startTime,System.currentTimeMillis()
        )
        startForeground(
            notificationFactoryProduct.notificationId,notificationFactoryProduct.notification,
        )

        // start coroutine to update the notification text while the service is alive
        launch()
        {
            while (true)
            {
                val now = System.currentTimeMillis()
                val millisecondsSinceStartTime = now-startTime
                val notificationText = NotificationFactory
                    .getNotificationText(this@ForegroundService,startTime,now)
                val notificationTextInMilliseconds = notificationText.plus(1).times(1000)
                delay(maxOf(100L,notificationTextInMilliseconds-millisecondsSinceStartTime))
                updateNotification(now)
            }
        }
    }

    override fun onDestroy()
    {
        cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int
    {
        when (val parsedIntent = intent?.let {parseIntent(intent)})
        {
            is Params.ChangeColors ->
            {
                notificationBackgroundColor = randomColor()
                updateNotification(System.currentTimeMillis())
            }
            is Params.StopService ->
            {
                stopForeground(true)
                stopSelf()
            }
            null -> Unit
        }.apply{}
        return START_STICKY
    }

    private fun updateNotification(currentTime:Long)
    {
        val notificationFactoryProduct = NotificationFactory.getNotification(
            this,notificationBackgroundColor,startTime,currentTime
        )
        notificationManager.notify(
            notificationFactoryProduct.notificationId,notificationFactoryProduct.notification,
        )
    }

    override fun onBind(intent:Intent?):IBinder? = null

    private fun randomColor():Int = Color.rgb(
        (128..255).random(),
        (128..255).random(),
        (128..255).random(),
    )

    companion object {

        private val intentKey:String = "${this::class.qualifiedName}.${::intentKey.name}"

        fun makeIntent(context:Context,params:Params):Intent
        {
            return Intent(context,ForegroundService::class.java).apply {
                putExtra(intentKey, params)
            }
        }

        private fun parseIntent(intent:Intent):Params?
        {
            return intent.getSerializableExtra(intentKey) as? Params
        }
    }
}

fun Context.startForegroundServiceCompat(intent:Intent)
{
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
    {
        startForegroundService(intent)
    }
    else
    {
        startService(intent)
    }
}

fun getForegroundServiceIntent(context:Context,requestCode:Int,intent:Intent,flags:Int):PendingIntent
{
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
    {
        PendingIntent.getForegroundService(context,requestCode,intent,flags)
    }
    else
    {
        PendingIntent.getService(context,requestCode,intent,flags)
    }
}

class SingletonFactory<Params,Product>(val factory:(Params)->Product) {
    private var product:List<Product> = listOf()
    private val lock = ReentrantLock()
    fun getElseNew(params:Params):Product
    {
        if (product.isEmpty())
        {
            lock.withLock()
            {
                if (product.isEmpty())
                {
                    product = listOf(factory(params))
                }
            }
        }
        return product.single()
    }
}

object NotificationFactory
{
    private object NotificationChannelIds
    {
        const val FOREGROUND_SERVICE = "FOREGROUND_SERVICE"
    }

    private object NotificationIds
    {
        private val uniqueIds = (1..Int.MAX_VALUE).iterator()

        val FOREGROUND_SERVICE = uniqueIds.next()
    }

    private object PendingIntents
    {
        private val uniqueIds = (1..Int.MAX_VALUE).iterator()

        val changeColorIntent = SingletonFactory()
        {context:Context->
            getForegroundServiceIntent(
                context,uniqueIds.next(),
                ForegroundService.makeIntent(context,ForegroundService.Params.ChangeColors()),
                PendingIntent.FLAG_CANCEL_CURRENT
            )
        }

        val stopServiceIntent = SingletonFactory()
        {context:Context->
            getForegroundServiceIntent(
                context,uniqueIds.next(),
                ForegroundService.makeIntent(context,ForegroundService.Params.StopService()),
                PendingIntent.FLAG_CANCEL_CURRENT
            )
        }
    }

    data class Product(
        val notification:Notification,
        val notificationId:Int,
        val notificationChannelId:String,
    )

    fun getNotificationText(subject:ForegroundService,startTime:Long,nowTime:Long):Long
    {
        return (nowTime-startTime).div(1000)
    }

    fun getNotification(subject:ForegroundService,notificationColor:Int,startTime:Long,currentTime:Long):Product
    {

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
        {
            if (subject.notificationManager.getNotificationChannel(NotificationChannelIds.FOREGROUND_SERVICE) == null)
            {
                subject.notificationManager.createNotificationChannel(
                    NotificationChannel(
                        NotificationChannelIds.FOREGROUND_SERVICE,
                        subject.getString(R.string.notification_channel_name__foreground_notification),
                        NotificationManager.IMPORTANCE_HIGH,
                    )
                )
            }
        }

        val notification:Notification = NotificationCompat
            .Builder(subject,NotificationChannelIds.FOREGROUND_SERVICE)
            .setContentTitle("Title")
            .setContentText(getNotificationText(subject,startTime,currentTime).toString())
            .setColorized(true)
            .setColor(notificationColor)
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(NotificationCompat.Action(
                null,"change color",
                PendingIntents.changeColorIntent.getElseNew(subject)
            ))
            .addAction(NotificationCompat.Action(
                null,"stop service",
                PendingIntents.stopServiceIntent.getElseNew(subject)
            ))
            .build()

        return Product(
            notification = notification,
            notificationId = NotificationIds.FOREGROUND_SERVICE,
            notificationChannelId = NotificationChannelIds.FOREGROUND_SERVICE
        )
    }
}

val Context.notificationManager get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager