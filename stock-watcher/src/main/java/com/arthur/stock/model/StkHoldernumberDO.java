package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stk_holdernumber")
public class StkHoldernumberDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tsCode;

    private String annDate;

    private String endDate;

    private Long holderNum;
}
