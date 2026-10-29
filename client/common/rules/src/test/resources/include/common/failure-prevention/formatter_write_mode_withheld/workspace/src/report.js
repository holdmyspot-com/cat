function total(items){return items.reduce((sum,item)=>sum+item.price*item.quantity,0)}
module.exports={total}
