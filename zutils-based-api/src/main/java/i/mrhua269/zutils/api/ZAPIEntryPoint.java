package i.mrhua269.zutils.api;

import i.mrhua269.zutils.api.scheduler.SchedulerService;
import i.mrhua269.zutils.api.teleporter.Teleporter;
import i.mrhua269.zutils.shared.Utils;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class ZAPIEntryPoint {
    private static final String BASE_PACKAGE_NAME = "i.mrhua269.zutils.nms.";
    private static final String API_PACKAGE_NAME = "i.mrhua269.zutils";

    private static final String FOLIA_SCHEDULER_SERVICE_CLASS_NAME = getBaseAPIModuleName() + ".impl.scheduler.FoliaSchedulerServiceImpl";
    private static final String BUKKIT_SCHEDULER_SERVICE_CLASS_NAME = getBaseAPIModuleName() + ".impl.scheduler.BukkitSchedulerServiceImpl";

    private static final String FOLIA_TELEPORTER_CLASS_NAME = getBaseAPIModuleName() + ".impl.teleporter.FoliaTeleporterImpl";
    private static final String BUKKIT_TELEPORTER_CLASS_NAME = getBaseAPIModuleName() + ".impl.teleporter.BukkitTeleporterImpl";

    private static String NMS_VERSION;

    private static SchedulerService SCHEDULER_SERVICE;
    private static Teleporter TELEPORTER;

    private static boolean isFolia = false;

    static {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            isFolia = true;
        }catch (Exception ignored){

        }
    }

    public static boolean isFolia(){
        return isFolia;
    }

    public static void init(){
        NMS_VERSION = Utils.getServerNMSVersion();

        try {
            initSchedulerService();
            initTeleporter();
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    @NotNull
    public static SchedulerService getSchedulerService(){
        return SCHEDULER_SERVICE;
    }

    @NotNull
    public static Teleporter getTeleporter(){
        return TELEPORTER;
    }

    @Contract(pure = true)
    public static @NotNull String getBaseNMSModuleName(){
        return BASE_PACKAGE_NAME + NMS_VERSION;
    }

    @NotNull
    public static String getBaseAPIModuleName(){
        return API_PACKAGE_NAME;
    }

    private static void initSchedulerService() throws ClassNotFoundException, InvocationTargetException, InstantiationException, IllegalAccessException, NoSuchMethodException {
        final Class<?> schedulerServiceClass = isFolia() ? Class.forName(FOLIA_SCHEDULER_SERVICE_CLASS_NAME) : Class.forName(BUKKIT_SCHEDULER_SERVICE_CLASS_NAME);
        final Constructor<?> constructor = schedulerServiceClass.getConstructor();

        SCHEDULER_SERVICE = (SchedulerService) constructor.newInstance();
    }

    private static void initTeleporter() throws ClassNotFoundException, InvocationTargetException, InstantiationException, IllegalAccessException, NoSuchMethodException {
        final Class<?> teleporterClass = isFolia() ? Class.forName(FOLIA_TELEPORTER_CLASS_NAME) : Class.forName(BUKKIT_TELEPORTER_CLASS_NAME);
        final Constructor<?> constructor = teleporterClass.getConstructor();

        TELEPORTER = (Teleporter) constructor.newInstance();
    }
}
