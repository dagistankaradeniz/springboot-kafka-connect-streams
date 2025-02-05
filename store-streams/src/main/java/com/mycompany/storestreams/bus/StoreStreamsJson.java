package com.mycompany.storestreams.bus;

import com.mycompany.commons.storeapp.events.Customer;
import com.mycompany.commons.storeapp.events.Order;
import com.mycompany.commons.storeapp.events.OrderDetailed;
import com.mycompany.commons.storeapp.events.OrderProduct;
import com.mycompany.commons.storeapp.events.OrderProductDetail;
import com.mycompany.commons.storeapp.events.Product;
import com.mycompany.commons.storeapp.events.ProductDetail;
import com.mycompany.storestreams.util.JsonSerdeFactory;
import lombok.extern.slf4j.Slf4j;
import ma.glasnost.orika.MapperFacade;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.Aggregator;
import org.apache.kafka.streams.kstream.Initializer;
import org.apache.kafka.streams.kstream.Joined;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Serialized;
import org.springframework.cloud.stream.annotation.EnableBinding;
import org.springframework.cloud.stream.annotation.Input;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
@Component
@Profile("!avro")
@EnableBinding(StoreKafkaStreamsProcessor.class)
public class StoreStreamsJson {

    private final MapperFacade mapperFacade;

    public StoreStreamsJson(MapperFacade mapperFacade) {
        this.mapperFacade = mapperFacade;
    }

    @StreamListener
    @SendTo(StoreKafkaStreamsProcessor.ORDER_OUTPUT)
    public KStream<String, OrderDetailed> process(
            @Input(StoreKafkaStreamsProcessor.CUSTOMER_INPUT) KTable<String, Customer> customerKTable,
            @Input(StoreKafkaStreamsProcessor.PRODUCT_INPUT) KTable<String, Product> productKTable,
            @Input(StoreKafkaStreamsProcessor.ORDER_INPUT) KStream<String, Order> orderIdKeyOrderValueKStream,
            @Input(StoreKafkaStreamsProcessor.ORDER_PRODUCT_INPUT) KStream<String, OrderProduct> orderIdKeyOrderProductValueKStream) {

        //--
        // Useful for logging
        //
        customerKTable.toStream().foreach((key, value) -> log.info("key: {}, value: {}", key, value));
        productKTable.toStream().foreach((key, value) -> log.info("key: {}, value: {}", key, value));
        orderIdKeyOrderValueKStream.foreach((key, value) -> log.info("key: {}, value: {}", key, value));
        orderIdKeyOrderProductValueKStream.foreach((key, value) -> log.info("key: {}, value: {}", key, value));

        // --
        // Add customer info to OrderDetailed

        KStream<String, Order> customerIdKeyOrderValueKStream = orderIdKeyOrderValueKStream
                .map((s, order) -> new KeyValue<>(order.getCustomer_id().toString(), order));

        KStream<String, OrderDetailed> customerIdKeyOrderDetailedValueKStream = customerIdKeyOrderValueKStream
                .join(customerKTable, (order, customer) -> {
                    OrderDetailed orderDetailed = mapperFacade.map(order, OrderDetailed.class);
                    orderDetailed.setCustomer_name(customer.getName());
                    return orderDetailed;
                }, Joined.with(Serdes.String(), JsonSerdeFactory.orderSerde, JsonSerdeFactory.customerSerde));

        KStream<String, OrderDetailed> orderIdKeyOrderDetailedValueKStream = customerIdKeyOrderDetailedValueKStream
                .map((s, orderDetailed) -> new KeyValue<>(orderDetailed.getId(), orderDetailed));

        // --
        // Add product info to OrderProductDetail

        KStream<String, OrderProduct> productIdKeyOrderProductValueKStream = orderIdKeyOrderProductValueKStream
                .map((s, orderProduct) -> new KeyValue<>(orderProduct.getProduct_id().toString(), orderProduct));

        KStream<String, OrderProductDetail> productIdKeyOrderProductDetailValueKStream = productIdKeyOrderProductValueKStream
                .join(productKTable, (orderProduct, product) -> {
                    OrderProductDetail orderProductDetail = mapperFacade.map(orderProduct, OrderProductDetail.class);
                    orderProductDetail.setProduct_name(product.getName());
                    return orderProductDetail;
                }, Joined.with(Serdes.String(), JsonSerdeFactory.orderProductSerde, JsonSerdeFactory.productSerde));

        KStream<String, OrderProductDetail> orderIdKeyOrderProductDetailValueKStream = productIdKeyOrderProductDetailValueKStream
                .map((s, orderProductDetail) -> new KeyValue<>(orderProductDetail.getOrder_id(), orderProductDetail));

        // --
        // Extract OrderProductDetail to ProductDetail

        KStream<String, ProductDetail> orderIdKeyProductDetailValueKStream = orderIdKeyOrderProductDetailValueKStream
                .mapValues(orderProductDetail -> mapperFacade.map(orderProductDetail, ProductDetail.class));

        // --
        // Aggregate (in a Set) ProductDetail by order

        KTable<String, Set> orderIdKeyOrderProductDetailSetValueKTable = orderIdKeyProductDetailValueKStream
                .groupByKey(Serialized.with(Serdes.String(), JsonSerdeFactory.productDetailSerde))
                .aggregate(new Initializer<Set>() {
                    @Override
                    public Set apply() {
                        return new LinkedHashSet();
                    }
                }, new Aggregator<String, ProductDetail, Set>() {
                    @Override
                    public Set apply(String s, ProductDetail productDetail, Set set) {
                        set.add(productDetail);
                        return set;
                    }
                }, Materialized.with(Serdes.String(), JsonSerdeFactory.setSerde));

        // --
        // Add ProductDetail Set to OrderDetailed

        orderIdKeyOrderDetailedValueKStream = orderIdKeyOrderDetailedValueKStream
                .join(orderIdKeyOrderProductDetailSetValueKTable, (orderDetailed, set) -> {
                    orderDetailed.setProducts(set);
                    return orderDetailed;
                }, Joined.with(Serdes.String(), JsonSerdeFactory.orderDetailedSerde, JsonSerdeFactory.setSerde));

        orderIdKeyOrderDetailedValueKStream.foreach((key, value) -> log.info("==> key: {}, value: {}", key, value));

        return orderIdKeyOrderDetailedValueKStream;
    }

}
