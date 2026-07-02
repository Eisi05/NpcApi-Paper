package de.eisi05.npc.api.utils.serialize;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

public class GsonDebugFactory implements TypeAdapterFactory
{
    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type)
    {
        TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);

        return new TypeAdapter<T>()
        {
            @Override
            public void write(JsonWriter out, T value) throws IOException
            {
                delegate.write(out, value);
            }

            @Override
            public T read(JsonReader in) throws IOException
            {
                System.out.println("[Gson Trace] Deserializing type: " + type.getType());
                T result = delegate.read(in);
                if(result != null)
                    System.out.println("[Gson Trace] -> Resolved into runtime class: " + result.getClass().getName());
                return result;
            }
        }.nullSafe();
    }
}