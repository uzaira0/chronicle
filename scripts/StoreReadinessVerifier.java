import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class StoreReadinessVerifier {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final Set<String> STORE_FORBIDDEN = Set.of(
        "android.permission.QUERY_ALL_PACKAGES",
        "android.permission.SCHEDULE_EXACT_ALARM"
    );

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: StoreReadinessVerifier <play|amazon> <manifest.xml> <privacy.properties>");
        }
        String channel = args[0];
        Path manifestPath = Path.of(args[1]);
        Path policyPath = Path.of(args[2]);
        Properties policy = loadProperties(policyPath);
        Document manifest = parseXml(manifestPath);

        require(channel.equals(policy.getProperty("channel")), "policy channel does not match invocation");
        LocalDate policySnapshot = LocalDate.parse(policy.getProperty("policy_snapshot"));
        LocalDate today = LocalDate.now();
        require(!policySnapshot.isAfter(today), "policy snapshot is in the future");
        require(ChronoUnit.DAYS.between(policySnapshot, today) <= 180, "policy snapshot is stale");

        NodeList sdkNodes = manifest.getElementsByTagName("uses-sdk");
        require(sdkNodes.getLength() == 1, "manifest must contain exactly one uses-sdk element");
        Element sdk = (Element) sdkNodes.item(0);
        require("23".equals(sdk.getAttributeNS(ANDROID, "minSdkVersion")), "minSdk must be 23");
        require("36".equals(sdk.getAttributeNS(ANDROID, "targetSdkVersion")), "targetSdk must be 36");

        Set<String> actual = permissionNames(manifest);
        String packageName = manifest.getDocumentElement().getAttribute("package");
        actual.remove(packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        Set<String> expected = csvSet(policy.getProperty("declared_permissions"));
        require(actual.equals(expected), "permission disclosure drift: expected=" + expected + " actual=" + actual);
        for (String forbidden : STORE_FORBIDDEN) {
            require(!actual.contains(forbidden), channel + " manifest contains forbidden permission " + forbidden);
        }

        if (channel.equals("amazon")) {
            String xml = Files.readString(manifestPath);
            require(!xml.contains("com.google.android.gms"), "Amazon manifest contains a GMS component");
            require(!xml.contains("androidx.health.connect"), "Amazon manifest contains a Health Connect component");
            require(actual.stream().noneMatch(p -> p.startsWith("android.permission.health.")),
                "Amazon manifest contains a Health Connect permission");
        } else if (channel.equals("play")) {
            String xml = Files.readString(manifestPath);
            Element manifestElement = manifest.getDocumentElement();
            require(policy.getProperty("package_name").equals(packageName),
                "Play package does not match the submission policy");
            require(policy.getProperty("version_code").equals(manifestElement.getAttributeNS(ANDROID, "versionCode")),
                "Play versionCode does not match the submission policy");
            require(policy.getProperty("version_name").equals(manifestElement.getAttributeNS(ANDROID, "versionName")),
                "Play versionName does not match the submission policy");
            for (String forbidden : Set.of(
                    "android.permission.ACTIVITY_RECOGNITION",
                    "com.google.android.gms.permission.ACTIVITY_RECOGNITION",
                    "android.permission.HIGH_SAMPLING_RATE_SENSORS",
                    "android.permission.BIND_ACCESSIBILITY_SERVICE",
                    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
                    "HealthConnectRationaleActivity",
                    "HardwareSensorService",
                    "InteractionCollectionService",
                    "NotificationListener")) {
                require(!xml.contains(forbidden), "Play manifest contains excluded capability " + forbidden);
            }
            require(actual.stream().noneMatch(p -> p.startsWith("android.permission.health.")),
                "Play manifest contains a Health Connect permission");
            require(xml.contains("DeviceUnlockMonitoringService"),
                "Play manifest must retain unlock user monitoring");
            require(xml.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"),
                "unlock user monitoring must retain its special-use disclosure");
            require(actual.contains("android.permission.PACKAGE_USAGE_STATS"),
                "Play manifest must retain Usage Access for core app-usage collection");
            verifyPlayAssets(policyPath.getParent().resolve("assets"));
        }
        if (channel.equals("play")) {
            require("false".equals(policy.getProperty("deletion_request_supported")),
                "Play must not claim an in-app or uninstall-triggered server deletion request");
        } else {
            require("true".equals(policy.getProperty("deletion_request_supported")),
                "deletion workflow must be disclosed");
        }
        require("false".equals(policy.getProperty("sold")), "sold must be explicitly false");
        require("false".equals(policy.getProperty("advertising")), "advertising must be explicitly false");
        System.out.println("Store readiness manifest/disclosure checks passed for " + channel + ": " + manifestPath);
    }

    private static Document parseXml(Path path) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties result = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            result.load(input);
        }
        return result;
    }

    private static void verifyPlayAssets(Path assetsDirectory) throws IOException {
        Path iconPath = assetsDirectory.resolve("app-icon-512.png");
        Path featureGraphicPath = assetsDirectory.resolve("feature-graphic.png");

        BufferedImage icon = readPng(iconPath, "Play app icon");
        require(icon.getWidth() == 512 && icon.getHeight() == 512,
            "Play app icon must be exactly 512 x 512 pixels");
        require(icon.getColorModel().hasAlpha(),
            "Play app icon must be a 32-bit PNG with an alpha channel");
        require(Files.size(iconPath) <= 1024L * 1024L,
            "Play app icon must not exceed 1024 KB");
        require(cornerIsOpaque(icon, 0, 0)
                && cornerIsOpaque(icon, icon.getWidth() - 1, 0)
                && cornerIsOpaque(icon, 0, icon.getHeight() - 1)
                && cornerIsOpaque(icon, icon.getWidth() - 1, icon.getHeight() - 1),
            "Play app icon must be full-square artwork; Play applies its own corner mask and shadow");

        BufferedImage featureGraphic = readPng(featureGraphicPath, "Play feature graphic");
        require(featureGraphic.getWidth() == 1024 && featureGraphic.getHeight() == 500,
            "Play feature graphic must be exactly 1024 x 500 pixels");
        require(!featureGraphic.getColorModel().hasAlpha(),
            "Play feature graphic must be an opaque RGB PNG without an alpha channel");
    }

    private static BufferedImage readPng(Path path, String description) throws IOException {
        require(Files.isRegularFile(path) && Files.size(path) > 0,
            description + " is missing: " + path);
        BufferedImage image = ImageIO.read(path.toFile());
        require(image != null, description + " is not a readable PNG: " + path);
        return image;
    }

    private static boolean cornerIsOpaque(BufferedImage image, int x, int y) {
        return ((image.getRGB(x, y) >>> 24) & 0xff) == 0xff;
    }

    private static Set<String> permissionNames(Document document) {
        Set<String> result = new TreeSet<>();
        NodeList nodes = document.getElementsByTagName("uses-permission");
        for (int i = 0; i < nodes.getLength(); i++) {
            String name = ((Element) nodes.item(i)).getAttributeNS(ANDROID, "name");
            if (!name.isBlank()) result.add(name);
        }
        return result;
    }

    private static Set<String> csvSet(String csv) {
        require(csv != null && !csv.isBlank(), "declared_permissions is required");
        Set<String> result = new TreeSet<>();
        Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(result::add);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
