package com.yulong.chatagent.rag.retrieve;

import org.springframework.stereotype.Component;

@Component
public class PgVectorFormatter {

    public String format(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
