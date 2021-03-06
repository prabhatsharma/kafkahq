package org.kafkahq.models;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.TopicConfig;
import org.kafkahq.repositories.ConfigRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@ToString
@EqualsAndHashCode
public class Topic {
    public Topic(
        TopicDescription description,
        List<ConsumerGroup> consumerGroup,
        List<LogDir> logDirs,
        List<Partition.Offsets> offsets
    ) {
        this.name = description.name();
        this.internal = description.isInternal();
        this.consumerGroups = consumerGroup;

        for (TopicPartitionInfo partition : description.partitions()) {
            this.partitions.add(new Partition(
                description.name(),
                partition,
                logDirs.stream()
                    .filter(logDir -> logDir.getPartition() == partition.partition())
                    .collect(Collectors.toList()),
                offsets.stream()
                    .filter(offset -> offset.getPartition() == partition.partition())
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException(
                        "Partition Offsets '" + partition.partition() + "' doesn't exist for topic " + this.name
                    ))
            ));
        }
    }

    private String name;

    public String getName() {
        return name;
    }

    private boolean internal;

    public boolean isInternal() {
        return internal;
    }

    private final List<Partition> partitions = new ArrayList<>();

    public List<Partition> getPartitions() {
        return partitions;
    }

    private List<ConsumerGroup> consumerGroups;

    public List<ConsumerGroup> getConsumerGroups() {
        return consumerGroups;
    }

    public List<Node.Partition> getReplicas() {
        return this.getPartitions().stream()
            .flatMap(partition -> partition.getNodes().stream())
            .distinct()
            .collect(Collectors.toList());
    }

    public List<Node.Partition> getInSyncReplicas() {
        return this.getPartitions().stream()
            .flatMap(partition -> partition.getNodes().stream())
            .filter(Node.Partition::isInSyncReplicas)
            .distinct()
            .collect(Collectors.toList());
    }

    public List<LogDir> getLogDir() {
        return this.getPartitions().stream()
            .flatMap(partition -> partition.getLogDir().stream())
            .collect(Collectors.toList());
    }

    public long getLogDirSize() {
        return this.getPartitions().stream()
            .map(Partition::getLogDirSize)
            .reduce(0L, Long::sum);
    }

    public long getSize() {
        return this.getPartitions().stream()
            .map(partition -> partition.getLastOffset() - partition.getFirstOffset())
            .reduce(0L, Long::sum);
    }

    public long getSize(int partition) {
        for (Partition current : this.getPartitions()) {
            if (partition == current.getId()) {
                return current.getLastOffset() - current.getFirstOffset();
            }
        }

        throw new NoSuchElementException("Partition '" + partition + "' doesn't exist for topic " + this.name);
    }

    public Boolean canDeleteRecords(ConfigRepository configRepository) throws ExecutionException, InterruptedException {
        if (this.isInternal()) {
            return false;
        }

        return configRepository
            .findByTopic(this.getName())
            .stream()
            .filter(config -> config.getName().equals(TopicConfig.CLEANUP_POLICY_CONFIG))
            .anyMatch(config -> config.getValue().contains(TopicConfig.CLEANUP_POLICY_COMPACT));
    }
}
