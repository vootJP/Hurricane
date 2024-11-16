package haven.automated.cookbook;

import haven.*;
import haven.res.ui.tt.q.qbuff.QBuff;
import haven.resutil.FoodInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

public class RecipeCollector {
    private static String cookbookURL = Utils.getpref("cookBookURL", "");
    private static String cookbookToken = Utils.getpref("cookBookToken", "");

    private static final Map<String, ParsedFoodInfo> cachedItems = new ConcurrentHashMap<>();
    private static final Queue<HashedFoodInfo> sendQueue = new ConcurrentLinkedQueue<>();
    public static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    static {
        scheduler.scheduleAtFixedRate(RecipeCollector::sendItems, 10L, 10, TimeUnit.SECONDS);
    }

    public static void sendToHttpServer(List<ParsedFoodInfo> foodInfos) {
        JSONArray jsonArray = new JSONArray();
        for (ParsedFoodInfo foodInfo : foodInfos) {
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("hash", foodInfo.hash);
            jsonObject.put("name", foodInfo.itemName);
            jsonObject.put("resource", foodInfo.resourceName);
            jsonObject.put("energy", foodInfo.energy);
            jsonObject.put("hunger", foodInfo.hunger);
            jsonObject.put("total_fep", foodInfo.totalFep);
            jsonObject.put("fep_hunger_ratio", foodInfo.fepHungerRatio);
            jsonObject.put("str1", foodInfo.str1);
            jsonObject.put("str2", foodInfo.str2);
            jsonObject.put("agi1", foodInfo.agi1);
            jsonObject.put("agi2", foodInfo.agi2);
            jsonObject.put("int1", foodInfo.int1);
            jsonObject.put("int2", foodInfo.int2);
            jsonObject.put("con1", foodInfo.con1);
            jsonObject.put("con2", foodInfo.con2);
            jsonObject.put("per1", foodInfo.per1);
            jsonObject.put("per2", foodInfo.per2);
            jsonObject.put("cha1", foodInfo.cha1);
            jsonObject.put("cha2", foodInfo.cha2);
            jsonObject.put("dex1", foodInfo.dex1);
            jsonObject.put("dex2", foodInfo.dex2);
            jsonObject.put("wil1", foodInfo.wil1);
            jsonObject.put("wil2", foodInfo.wil2);
            jsonObject.put("psy1", foodInfo.psy1);
            jsonObject.put("psy2", foodInfo.psy2);

            JSONArray ingredientsArray = new JSONArray();
            for (ParsedFoodInfo.FoodIngredient ingredient : foodInfo.ingredients) {
                JSONObject ingredientObject = new JSONObject();
                ingredientObject.put("name", ingredient.name);
                ingredientObject.put("percentage", ingredient.percentage);
                ingredientsArray.put(ingredientObject);
            }
            jsonObject.put("ingredients", ingredientsArray);

            jsonArray.put(jsonObject);
        }

        try {
            URL apiUrl = new URL(cookbookURL);
            HttpURLConnection connection = (HttpURLConnection) apiUrl.openConnection();

            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + cookbookToken);
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonArray.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED
                    || responseCode == HttpURLConnection.HTTP_ACCEPTED) {
//                todo rather annoying spam so temp. disabled.
//                System.out.println("Successfully sent " + foodInfos.size() + " new recipes.");
            } else {
                System.err.println("Failed to send food items. HTTP Response Code: " + responseCode);
                try (InputStream errorStream = connection.getErrorStream()) {
                    if (errorStream != null) {
                        String errorResponse = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                        System.err.println("Error Response: " + errorResponse);
                    }
                }
            }
            connection.disconnect();
        } catch (IOException e) {
            System.err.println("IOException while sending HTTP request: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Unexpected exception while sending HTTP request: " + e.getMessage());
        }
    }

    private static void sendItems() {
        if (sendQueue.isEmpty()) {
            return;
        }
        List<ParsedFoodInfo> toSend = new ArrayList<>();
        while (!sendQueue.isEmpty()) {
            HashedFoodInfo info = sendQueue.poll();
            if (cachedItems.containsKey(info.hash)) {
                continue;
            }
            cachedItems.put(info.hash, info.foodInfo);
            toSend.add(info.foodInfo);
        }

        if (!toSend.isEmpty()) {
            sendToHttpServer(toSend);
        }
    }

    private static void checkAndSend(ParsedFoodInfo info) {
        String hash = generateHash(info);
        if (cachedItems.containsKey(hash)) {
            return;
        }
        info.hash = hash;
        sendQueue.add(new HashedFoodInfo(hash, info));
    }

    private static String generateHash(ParsedFoodInfo foodInfo) {
        try {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(foodInfo.itemName).append(";")
                    .append(foodInfo.resourceName).append(";");
            foodInfo.ingredients.forEach(it -> stringBuilder.append(it.name).append(";").append(it.percentage).append(";"));
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(stringBuilder.toString().getBytes(StandardCharsets.UTF_8));
            return getHex(hash);
        } catch (NoSuchAlgorithmException e) {
            System.out.println("Cannot generate food hash");
        }
        return null;
    }

    private static String getHex(byte[] bytes) {
        BigInteger bigInteger = new BigInteger(1, bytes);
        return bigInteger.toString(16);
    }

    public static boolean checkForHempBuff(GameUI gui){
        for(Widget buff : gui.buffs.children()){
            if(buff instanceof Buff && ((Buff) buff).res != null){
                if(((Buff) buff).res.get().name.equals("gfx/hud/buffs/ganja")){
                    return true;
                }
            }
        }
        return false;
    }

    private static double round2Dig(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public static void processRecipe(GameUI gui, List<ItemInfo> infoList, String resName){
        if(checkForHempBuff(gui)){
            return;
        }
        try {
            FoodInfo foodInfo = ItemInfo.find(FoodInfo.class, infoList);
            if (foodInfo != null) {
                QBuff qBuff = ItemInfo.find(QBuff.class, infoList);
                double quality = qBuff != null ? qBuff.q : 10.0;
                double fepMultiplier = Math.sqrt(quality / 10.0);
                double hungerMultiplier = Math.sqrt(fepMultiplier);

                ParsedFoodInfo parsedFoodInfo = new ParsedFoodInfo();
                parsedFoodInfo.resourceName = resName;
                parsedFoodInfo.energy = (int) (Math.round(foodInfo.end * 100));
                parsedFoodInfo.hunger = round2Dig(foodInfo.glut * 1000 / hungerMultiplier);
                double totalFep = 0.0;
                for (int i = 0; i < foodInfo.evs.length; i++) {
                    double value = Math.round((foodInfo.evs[i].a) / fepMultiplier * 1000.0) / 1000.0;
                    totalFep += value;

                    switch (foodInfo.evs[i].ev.nm) {
                        case "Strength +1" -> parsedFoodInfo.str1 = value;
                        case "Strength +2" -> parsedFoodInfo.str2 = value;
                        case "Agility +1" -> parsedFoodInfo.agi1 = value;
                        case "Agility +2" -> parsedFoodInfo.agi2 = value;
                        case "Intelligence +1" -> parsedFoodInfo.int1 = value;
                        case "Intelligence +2" -> parsedFoodInfo.int2 = value;
                        case "Constitution +1" -> parsedFoodInfo.con1 = value;
                        case "Constitution +2" -> parsedFoodInfo.con2 = value;
                        case "Perception +1" -> parsedFoodInfo.per1 = value;
                        case "Perception +2" -> parsedFoodInfo.per2 = value;
                        case "Charisma +1" -> parsedFoodInfo.cha1 = value;
                        case "Charisma +2" -> parsedFoodInfo.cha2 = value;
                        case "Dexterity +1" -> parsedFoodInfo.dex1 = value;
                        case "Dexterity +2" -> parsedFoodInfo.dex2 = value;
                        case "Will +1" -> parsedFoodInfo.wil1 = value;
                        case "Will +2" -> parsedFoodInfo.wil2 = value;
                        case "Psyche +1" -> parsedFoodInfo.psy1 = value;
                        case "Psyche +2" -> parsedFoodInfo.psy2 = value;
                        default -> {
                            return;
                        }
                    }
                }
                parsedFoodInfo.totalFep = Math.round(totalFep * 1000.0) / 1000.0;
                parsedFoodInfo.fepHungerRatio = round2Dig(parsedFoodInfo.hunger != 0 ? (parsedFoodInfo.totalFep / parsedFoodInfo.hunger) : 0);
                for (ItemInfo info : infoList) {
                    if (info instanceof ItemInfo.AdHoc) {
                        String text = ((ItemInfo.AdHoc) info).str.text;
                        if (text.equals("White-truffled")
                                || text.equals("Black-truffled")
                                || text.equals("Peppered")
                                || text.contains("Propolis")) {
                            //with some exceptions made for some crafting buffs which expliclity wish to modify the FEP-to-Hunger ratio, e.g. "Propolis".
                            //todo - Not a failsafe in any way but hey ho will see if you can use it on item like pepper or only as ingredient...
                            return;
                        }
                    }
                    if (info instanceof ItemInfo.Name) {
                        parsedFoodInfo.itemName = ((ItemInfo.Name) info).str.text;
                    }
                    if (info.getClass().getName().contains("Ingredient")) {
                        String name = (String) info.getClass().getField("name").get(info);
                        Double value = (Double) info.getClass().getField("val").get(info);
                        parsedFoodInfo.ingredients.add(new ParsedFoodInfo.FoodIngredient(name, (int) (value * 100)));
                    } else if (info.getClass().getName().contains("Smoke")) {
                        String name = (String) info.getClass().getField("name").get(info);
                        Double value = (Double) info.getClass().getField("val").get(info);
                        parsedFoodInfo.ingredients.add(new ParsedFoodInfo.FoodIngredient(name, (int) (value * 100)));
                    }
                }
                checkAndSend(parsedFoodInfo);
            }
        } catch (Exception ignored) {}
    }

    private static class HashedFoodInfo {
        public String hash;
        public ParsedFoodInfo foodInfo;

        public HashedFoodInfo(String hash, ParsedFoodInfo foodInfo) {
            this.hash = hash;
            this.foodInfo = foodInfo;
        }
    }
}
