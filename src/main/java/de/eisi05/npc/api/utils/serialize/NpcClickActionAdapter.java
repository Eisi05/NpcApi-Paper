package de.eisi05.npc.api.utils.serialize;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import de.eisi05.npc.api.interfaces.NpcClickAction;

import java.io.*;
import java.util.Base64;
import java.util.Map;

public class NpcClickActionAdapter extends TypeAdapter<NpcClickAction>
{
    @Override
    public void write(JsonWriter out, NpcClickAction value) throws IOException
    {
        if(value == null)
        {
            out.nullValue();
            return;
        }

        Class<?> clazz = value.getClass();
        out.beginObject();

        if(clazz.isSynthetic() || clazz.getName().contains("$$Lambda$") || !Serializable.class.isAssignableFrom(clazz))
        {
            try(ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                ObjectOutputStream objOut = new ObjectOutputStream(byteOut))
            {
                objOut.writeObject(value);
                String base64Bytes = Base64.getEncoder().encodeToString(byteOut.toByteArray());
                out.name("bytes").value(base64Bytes);
            }
            catch(Exception e)
            {
                throw new IOException("Failed to natively serialize dynamic NpcClickAction lambda expression", e);
            }
        }
        else
        {
            out.name("className").value(clazz.getName());

            JsonElement delegateElement = ObjectSaver.CLEAN_GSON.toJsonTree(value, clazz);
            if(delegateElement.isJsonObject())
            {
                JsonObject jsonObject = delegateElement.getAsJsonObject();
                for(Map.Entry<String, JsonElement> entry : jsonObject.entrySet())
                {
                    if(entry.getKey().equals("className") || entry.getKey().equals("bytes"))
                        continue;

                    out.name(entry.getKey());
                    ObjectSaver.CLEAN_GSON.toJson(entry.getValue(), out);
                }
            }
        }

        out.endObject();
    }

    @Override
    public NpcClickAction read(JsonReader in) throws IOException
    {
        if(in.peek() == com.google.gson.stream.JsonToken.NULL)
        {
            in.nextNull();
            return null;
        }

        JsonObject jsonObject = JsonParser.parseReader(in).getAsJsonObject();

        if(jsonObject.has("bytes"))
        {
            String base64Bytes = jsonObject.get("bytes").getAsString();
            byte[] bytes = Base64.getDecoder().decode(base64Bytes);

            try(ByteArrayInputStream byteIn = new ByteArrayInputStream(bytes);
                ObjectInputStream objIn = new ObjectInputStream(byteIn))
            {
                NpcClickAction action = (NpcClickAction) objIn.readObject();
                if(action != null)
                    return action.initialize();
                return action;
            }
            catch(ClassNotFoundException e)
            {
                throw new JsonParseException("Failed to map compiled runtime lambda target context dependencies.", e);
            }
        }

        if(jsonObject.has("className"))
        {
            String className = jsonObject.get("className").getAsString();
            try
            {
                Class<?> targetClass = Class.forName(className);
                NpcClickAction action = (NpcClickAction) ObjectSaver.CLEAN_GSON.fromJson(jsonObject, targetClass);

                if(action != null)
                    return action.initialize();

                return action;
            }
            catch(ClassNotFoundException e)
            {
                throw new JsonParseException("Could not track down registered class definition: " + className, e);
            }
        }

        throw new JsonParseException("Invalid NpcClickAction JSON object structure. Must contain either 'className' or 'bytes'.");
    }
}