package com.spends.app.data.db

import androidx.room.TypeConverter
import com.spends.app.domain.model.CategoryUsage
import com.spends.app.domain.model.Direction
import com.spends.app.domain.model.PaymentMethodType
import com.spends.app.domain.model.RecurrenceFreq
import com.spends.app.domain.model.TxnKind
import com.spends.app.domain.model.TxnSource

/** Stores domain enums as their stable name strings. */
class Converters {

    @TypeConverter fun usageToString(value: CategoryUsage): String = value.name
    @TypeConverter fun stringToUsage(value: String): CategoryUsage = CategoryUsage.valueOf(value)
    @TypeConverter fun kindToString(value: TxnKind): String = value.name
    @TypeConverter fun stringToKind(value: String): TxnKind = TxnKind.valueOf(value)

    @TypeConverter fun directionToString(value: Direction): String = value.name
    @TypeConverter fun stringToDirection(value: String): Direction = Direction.valueOf(value)

    @TypeConverter fun sourceToString(value: TxnSource): String = value.name
    @TypeConverter fun stringToSource(value: String): TxnSource = TxnSource.valueOf(value)

    @TypeConverter fun pmTypeToString(value: PaymentMethodType): String = value.name
    @TypeConverter fun stringToPmType(value: String): PaymentMethodType = PaymentMethodType.valueOf(value)

    @TypeConverter fun freqToString(value: RecurrenceFreq): String = value.name
    @TypeConverter fun stringToFreq(value: String): RecurrenceFreq = RecurrenceFreq.valueOf(value)
}
