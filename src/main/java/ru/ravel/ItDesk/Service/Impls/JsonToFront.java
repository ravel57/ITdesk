package ru.ravel.ItDesk.Service.Impls;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.springframework.stereotype.Service;

@Service
public class JsonToFront {

    public JsonToFront(){

    }

//    @Override
    public String toString(Object o){
        Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
        return gson.toJson(o).replace('\"', '\'');
    }

}
