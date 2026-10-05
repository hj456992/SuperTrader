package dev.ailiao.expert;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.BooleanSupplier;

interface ProductionModel {
    ObjectNode json(String purpose,ObjectNode input,BooleanSupplier cancelled) throws Exception;
}
