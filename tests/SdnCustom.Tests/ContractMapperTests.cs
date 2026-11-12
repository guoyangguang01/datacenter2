using SdnCustom.Contracts;
using SdnCustom.Shared.Model;

namespace SdnCustom.Tests;

public sealed class ContractMapperTests
{
    [Theory]
    [InlineData(PointDataType.Bool, true)]
    [InlineData(PointDataType.Int32, 42)]
    [InlineData(PointDataType.Float64, 3.14)]
    [InlineData(PointDataType.String, "RUN")]
    public void RoundTripsTypedValues(PointDataType dataType, object value)
    {
        var contract = ContractMapper.ToContractValue(value);
        var restored = ContractMapper.FromContractValue(contract, dataType);

        if (value is double number)
        {
            Assert.Equal(number, Assert.IsType<double>(restored), 6);
        }
        else
        {
            Assert.Equal(value, restored);
        }
    }

    [Fact]
    public void RoundTripsPointDefinition()
    {
        var source = new PointDefinition
        {
            PointId = "P1",
            PointName = "Temperature",
            Address = "ns=2;s=Temperature",
            DataType = PointDataType.Float64,
            Unit = "C",
            Direction = PointDirection.Input,
            SourceOutputPointId = "A:OUT",
            Deadband = 0.2,
            Group = "Fuel"
        };

        var restored = ContractMapper.FromContract(ContractMapper.ToContract(source));

        Assert.Equal(source, restored);
    }
}
